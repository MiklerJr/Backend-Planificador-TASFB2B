package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.dto.auditoria.EstimacionAuditoria;
import com.tasfb2b.planificador.dto.vuelos.VueloCancelado;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;
import com.tasfb2b.planificador.servicios.persistencia.LectorSolucionBd;
import com.tasfb2b.planificador.servicios.persistencia.PersistenciaSolucionService;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.MapeadorAlgoritmo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AuditoriaCorridaService {

    private final RegistroJobs jobs;
    private final AuditoriaService auditoria;
    private final PersistenciaSolucionService persistencia;
    private final LectorSolucionBd solucionBdReader;
    private final MotorGrafoCache motorCache;
    private final MapeadorAlgoritmo mapper;
    private final CargadorDatos cargadorDatos;

    public AuditoriaCorridaService(RegistroJobs jobs,
                                   AuditoriaService auditoria,
                                   PersistenciaSolucionService persistencia,
                                   LectorSolucionBd solucionBdReader,
                                   MotorGrafoCache motorCache,
                                   MapeadorAlgoritmo mapper,
                                   CargadorDatos cargadorDatos) {
        this.jobs = jobs;
        this.auditoria = auditoria;
        this.persistencia = persistencia;
        this.solucionBdReader = solucionBdReader;
        this.motorCache = motorCache;
        this.mapper = mapper;
        this.cargadorDatos = cargadorDatos;
    }

    public record ResultadoAuditoria(Path path, int filas, String error,
                                     LocalDateTime desdeEfectivo, LocalDateTime hastaEfectivo,
                                     boolean recortado) {
        public static ResultadoAuditoria ok(Path p, int f, LocalDateTime d, LocalDateTime h, boolean rec) {
            return new ResultadoAuditoria(p, f, null, d, h, rec);
        }
        public static ResultadoAuditoria error(String e) {
            return new ResultadoAuditoria(null, 0, e, null, null, false);
        }
        public boolean disponible() { return error == null; }
    }

    public record ResultadoEstimacion(EstimacionAuditoria estimacion, String error) {
        public static ResultadoEstimacion ok(EstimacionAuditoria e) { return new ResultadoEstimacion(e, null); }
        public static ResultadoEstimacion error(String e) { return new ResultadoEstimacion(null, e); }
        public boolean disponible() { return error == null; }
    }

    public record RangoAuditoria(LocalDateTime desde, LocalDateTime hasta, boolean recortado) {}

    private RangoAuditoria resolverRangoAuditoria(EstadoJob job, LocalDateTime desde, LocalDateTime hasta) {
        if (desde != null && hasta != null && !desde.isBefore(hasta)) {
            throw new ParametroInvalidoException(
                    "rango inválido: 'desde' (" + desde + ") debe ser anterior a 'hasta' (" + hasta + ")");
        }
        LocalDateTime ini = job.ventanaInicioUtc;
        LocalDateTime fin = job.ventanaFinUtc;
        if (ini == null || fin == null) return new RangoAuditoria(desde, hasta, false);   // sin ventana: no verificar
        boolean sinSolape = (desde != null && !desde.isBefore(fin)) || (hasta != null && !hasta.isAfter(ini));
        if (sinSolape) {
            throw new ParametroInvalidoException(
                    "el rango pedido no se solapa con la ventana simulada [" + ini + ", " + fin + ")");
        }
        LocalDateTime d = desde;
        LocalDateTime h = hasta;
        boolean recortado = false;
        if (desde != null && desde.isBefore(ini)) { d = ini; recortado = true; }
        if (hasta != null && hasta.isAfter(fin))  { h = fin; recortado = true; }
        return new RangoAuditoria(d, h, recortado);
    }

    public ResultadoAuditoria generarAuditoriaZip(String jobId, LocalDateTime desde, LocalDateTime hasta) {
        EstadoJob job = jobs.get(jobId);
        if (job == null) return ResultadoAuditoria.error("job inexistente");
        if (auditoria == null) return ResultadoAuditoria.error("auditoría no disponible (sin servicio de auditoría)");
        if (RegistroJobs.ESTADOS_ACTIVOS.contains(job.estado)) {
            return ResultadoAuditoria.error("el job aún está activo; la auditoría estará disponible al terminar");
        }
        if (!persistencia.reflejaEnBd(jobId)) {
            return ResultadoAuditoria.error(
                    "la solución de este job ya fue reemplazada por una corrida posterior; auditoría no disponible");
        }
        RangoAuditoria rango = resolverRangoAuditoria(job, desde, hasta);
        if (!persistencia.tomarParaLectura(jobId)) {
            return ResultadoAuditoria.error("hay otra corrida tomando la persistencia; reintenta en unos segundos");
        }
        try {
            Grafo graph = motorCache.obtenerGrafo(
                    () -> mapper.mapearAGrafo(cargadorDatos.getAeropuertos(), cargadorDatos.getVuelos()));
            Map<String, Arista> indiceVuelo = solucionBdReader.construirIndiceVuelo(graph);
            List<VueloCancelado> cancelaciones =
                    solucionBdReader.leerCancelaciones(indiceVuelo, rango.desde(), rango.hasta());
            List<LoteEnvio> sinRuta = filtrarSinRutaPorRango(job.auditoriaSinRuta, rango.desde(), rango.hasta());
            java.util.function.Consumer<java.util.function.Consumer<LoteEnvio>> fuenteEnrutados =
                    sink -> solucionBdReader.paraCadaEnrutado(indiceVuelo, rango.desde(), rango.hasta(), sink);

            Thread.interrupted();
            job.borrarZip();
            Path path = Files.createTempFile("planificador-auditoria-" + jobId + "-", ".zip");
            path.toFile().deleteOnExit();
            log.info("Generando auditoria ZIP on-demand (job {}, desde={}, hasta={}, recortado={})",
                    jobId, rango.desde(), rango.hasta(), rango.recortado());
            int filas = auditoria.escribirZipStreaming(path, AuditoriaService.FILAS_POR_ARCHIVO, jobId,
                    fuenteEnrutados, sinRuta, cancelaciones);
            job.auditoriaZipPath = path;
            job.auditoriaCsvPath = null;
            job.auditoriaCsv = null;
            job.auditoriaFilas = filas;
            log.info("Auditoria ZIP on-demand generada: {} filas (job {}) en {}", filas, jobId, path);
            return ResultadoAuditoria.ok(path, filas, rango.desde(), rango.hasta(), rango.recortado());
        } catch (IOException e) {
            log.error("No se pudo generar auditoria ZIP on-demand (job {}): {}", jobId, e.getMessage());
            return ResultadoAuditoria.error("error generando la auditoría: " + e.getMessage());
        } finally {
            persistencia.finalizarCorrida(jobId);
        }
    }

    public ResultadoEstimacion estimarAuditoria(String jobId, LocalDateTime desde, LocalDateTime hasta) {
        EstadoJob job = jobs.get(jobId);
        if (job == null) return ResultadoEstimacion.error("job inexistente");
        if (RegistroJobs.ESTADOS_ACTIVOS.contains(job.estado)) {
            return ResultadoEstimacion.error("el job aún está activo; la auditoría estará disponible al terminar");
        }
        if (!persistencia.reflejaEnBd(jobId)) {
            return ResultadoEstimacion.error(
                    "la solución de este job ya fue reemplazada por una corrida posterior; auditoría no disponible");
        }
        RangoAuditoria rango = resolverRangoAuditoria(job, desde, hasta);
        long enrutados = solucionBdReader.contarEnrutados(rango.desde(), rango.hasta());
        long sinRuta = filtrarSinRutaPorRango(job.auditoriaSinRuta, rango.desde(), rango.hasta()).size();
        long filasEnvios = enrutados + sinRuta;
        long cancelaciones = solucionBdReader.contarCancelaciones(rango.desde(), rango.hasta());
        int filasPorArchivo = AuditoriaService.FILAS_POR_ARCHIVO;
        int csvEnvios = (int) Math.ceil(filasEnvios / (double) filasPorArchivo);
        int csvCancelaciones = 1;
        EstimacionAuditoria est = new EstimacionAuditoria(
                filasEnvios, csvEnvios, cancelaciones, csvCancelaciones,
                csvEnvios + csvCancelaciones, filasPorArchivo,
                rango.desde() != null ? rango.desde().toString() : null,
                rango.hasta() != null ? rango.hasta().toString() : null,
                rango.recortado());
        return ResultadoEstimacion.ok(est);
    }

    private static List<LoteEnvio> filtrarSinRutaPorRango(List<LoteEnvio> sinRuta,
                                                             LocalDateTime desde, LocalDateTime hasta) {
        if (sinRuta == null || sinRuta.isEmpty()) return List.of();
        if (desde == null && hasta == null) return new ArrayList<>(sinRuta);
        List<LoteEnvio> out = new ArrayList<>();
        for (LoteEnvio b : sinRuta) {
            LocalDateTime ready = b.getTiempoListo();
            if (ready == null) continue;
            if (desde != null && ready.isBefore(desde)) continue;
            if (hasta != null && !ready.isBefore(hasta)) continue;
            out.add(b);
        }
        return out;
    }
}
