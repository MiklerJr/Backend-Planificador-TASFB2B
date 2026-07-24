package com.tasfb2b.planificador.servicios.jobs;


import com.tasfb2b.planificador.algoritmo.alns.RutaCandidata;

import com.tasfb2b.planificador.algoritmo.alns.CodificadorClaveVuelo;
import com.tasfb2b.planificador.algoritmo.alns.ContextoTemporal;
import com.tasfb2b.planificador.algoritmo.alns.GestorBacklog;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.dto.datos.AeropuertoAgregado;
import com.tasfb2b.planificador.dto.datos.AltaAeropuertoRequest;
import com.tasfb2b.planificador.dto.jobs.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.TipoEnvio;
import com.tasfb2b.planificador.servicios.AltasEnCalienteService;
import com.tasfb2b.planificador.servicios.persistencia.LectorSolucionBd;
import com.tasfb2b.planificador.servicios.persistencia.PersistenciaSolucionService;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.FragmentadorEnvios;
import com.tasfb2b.planificador.utilidades.validador.ValidadorEnvio;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operaciones EN VIVO sobre una corrida en curso (request-side): validan la petición del
 * operador y la ENCOLAN en el {@link EstadoJob}; el hilo del job las drena en la frontera
 * del siguiente bloque. No tocan el motor ni el grafo directamente.
 */
@Slf4j
@Service
public class OperacionesEnVivoService {

    private static final int SUFIJO_ROUTE_CANDIDATES = 5;

    private final RegistroJobs jobs;
    private final CargadorDatos cargadorDatos;
    private final AltasEnCalienteService altasEnCaliente;
    private final LectorSolucionBd solucionBdReader;
    private final PersistenciaSolucionService persistencia;
    private final PlanificadorProperties props;

    public OperacionesEnVivoService(RegistroJobs jobs,
                                    CargadorDatos cargadorDatos,
                                    AltasEnCalienteService altasEnCaliente,
                                    LectorSolucionBd solucionBdReader,
                                    PersistenciaSolucionService persistencia,
                                    PlanificadorProperties props) {
        this.jobs = jobs;
        this.cargadorDatos = cargadorDatos;
        this.altasEnCaliente = altasEnCaliente;
        this.solucionBdReader = solucionBdReader;
        this.persistencia = persistencia;
        this.props = props;
    }

    public boolean solicitarCancelacionVuelo(String jobId, CancelacionVueloRequest orden) {
        if (orden == null) return false;
        EstadoJob job = jobs.get(jobId);
        if (job == null) return false;
        if (!RegistroJobs.ESTADOS_ACTIVOS.contains(job.estado)) return false;
        if (job.encolarCancelacionVuelo(orden)) {
            log.info("Cancelación de vuelo encolada (job {}): {}->{} salida {}", jobId,
                    orden.getOrigen(), orden.getDestino(), orden.getFechaHoraSalida());
        } else {
            log.debug("Cancelación de vuelo duplicada ya pendiente (job {}): {}->{} salida {} — ignorada",
                    jobId, orden.getOrigen(), orden.getDestino(), orden.getFechaHoraSalida());
        }
        return true;
    }

    public int solicitarInyeccionEnvios(String jobId, InyeccionEnviosRequest req) {
        if (req == null || req.getEnvios() == null || req.getEnvios().isEmpty())
            throw new ParametroInvalidoException("inyección vacía: se requiere al menos un envío");
        EstadoJob job = jobs.get(jobId);
        if (job == null) return -1;
        for (InyeccionEnviosRequest.Item it : req.getEnvios()) {
            if (!ValidadorEnvio.camposObligatoriosPresentes(it.getOrigen(), it.getDestino()))
                throw new ParametroInvalidoException("origen y destino son obligatorios (RF03)");
            if (ValidadorEnvio.esMismoAeropuerto(it.getOrigen(), it.getDestino()))
                throw new ParametroInvalidoException("origen y destino no pueden ser iguales (RF02)");
            if (it.getCantidad() <= 0)
                throw new ParametroInvalidoException("la cantidad debe ser > 0");
            if (cargadorDatos.getAeropuerto(it.getOrigen()) == null)
                throw new ParametroInvalidoException("ICAO origen desconocido: " + it.getOrigen());
            if (cargadorDatos.getAeropuerto(it.getDestino()) == null)
                throw new ParametroInvalidoException("ICAO destino desconocido: " + it.getDestino());
        }
        if (!RegistroJobs.ESTADOS_ACTIVOS.contains(job.estado)) return -1;
        for (InyeccionEnviosRequest.Item it : req.getEnvios()) job.encolarInyeccion(it);
        log.info("Inyección de {} envío(s) encolada (job {})", req.getEnvios().size(), jobId);
        return req.getEnvios().size();
    }

    public Integer getOffsetAeropuerto(String icao) {
        if (cargadorDatos == null || icao == null) return null;
        Aeropuerto a = cargadorDatos.getAeropuerto(icao.trim());
        return (a == null) ? null : a.getOffsetHorario();
    }

    public boolean solicitarAltaVuelo(String jobId, AltaVueloRequest alta) {
        EstadoJob job = jobs.get(jobId);
        if (job == null) return false;
        if (altasEnCaliente == null)
            throw new ParametroInvalidoException("altas en caliente no disponibles");
        Set<String> icaosPendientes = new HashSet<>();
        for (AltaAeropuertoRequest p : job.getAltasAeropuertoPendientes()) {
            if (p.getIcao() != null) icaosPendientes.add(p.getIcao().trim());
        }
        altasEnCaliente.validarAltaVuelo(alta, icaosPendientes);
        if (!RegistroJobs.ESTADOS_ACTIVOS.contains(job.estado)) return false;

        String idVuelo = AltasEnCalienteService.idVueloDe(alta);
        for (AltaVueloRequest pendiente : job.getAltasVueloPendientes()) {
            if (idVuelo.equals(AltasEnCalienteService.idVueloDe(pendiente)))
                throw new ParametroInvalidoException("ya hay un alta pendiente con id " + idVuelo);
        }
        if (job.encolarAltaVuelo(alta)) {
            log.info("Alta de vuelo encolada (job {}): {} cap {}", jobId, idVuelo, alta.getCapacidad());
        } else {
            log.debug("Alta de vuelo duplicada ya pendiente (job {}): {} — ignorada", jobId, idVuelo);
        }
        return true;
    }

    public boolean solicitarAltaAeropuerto(String jobId, AltaAeropuertoRequest alta) {
        EstadoJob job = jobs.get(jobId);
        if (job == null) return false;
        if (altasEnCaliente == null)
            throw new ParametroInvalidoException("altas en caliente no disponibles");
        altasEnCaliente.validarAltaAeropuerto(alta);
        if (!RegistroJobs.ESTADOS_ACTIVOS.contains(job.estado)) return false;

        String icao = alta.getIcao().trim();
        for (AltaAeropuertoRequest pendiente : job.getAltasAeropuertoPendientes()) {
            if (pendiente.getIcao() != null && icao.equals(pendiente.getIcao().trim()))
                throw new ParametroInvalidoException("ya hay un alta pendiente con ICAO " + icao);
        }
        if (job.encolarAltaAeropuerto(alta)) {
            log.info("Alta de aeropuerto encolada (job {}): {} (huso {}, cap {})",
                    jobId, icao, alta.getHusoHorario(), alta.getCapacidad());
        } else {
            log.debug("Alta de aeropuerto duplicada ya pendiente (job {}): {} — ignorada", jobId, icao);
        }
        return true;
    }

    // ------------------------------------------------------------------ apply-side (hilo del job)

    public void aplicarAltasEnCaliente(EstadoJob job, Grafo graph, OperadorReparacionVoraz enrutador,
                                        int bloqueIdx) {
        if (job == null || altasEnCaliente == null) return;

        AltaAeropuertoRequest reqAero;
        while ((reqAero = job.getAltasAeropuertoPendientes().poll()) != null) {
            String motivo = altasEnCaliente.aplicarAltaAeropuerto(reqAero, graph, enrutador);
            AeropuertoAgregado info = new AeropuertoAgregado(
                    reqAero.getIcao(), reqAero.getCiudad(), reqAero.getHusoHorario(),
                    reqAero.getCapacidad(), reqAero.getContinente(), bloqueIdx, motivo);
            if (motivo == null) {
                job.getAeropuertosAgregados().add(info);
            } else {
                job.getAltasAeropuertoNoAplicadas().add(info);
                log.warn("Alta de aeropuerto NO aplicada (job {}, bloque {}): {}",
                        job.getJobId(), bloqueIdx, motivo);
            }
        }

        AltaVueloRequest req;
        while ((req = job.getAltasVueloPendientes().poll()) != null) {
            String motivo = altasEnCaliente.aplicarAltaVuelo(req, graph, enrutador);
            String idVuelo = null;
            try { idVuelo = AltasEnCalienteService.idVueloDe(req); } catch (Exception ignored) { }
            VueloAgregado info = new VueloAgregado(idVuelo, req.getOrigen(), req.getDestino(),
                    req.getHoraSalida(), req.getHoraLlegada(), req.getCapacidad(), bloqueIdx, motivo);
            if (motivo == null) {
                job.getVuelosAgregados().add(info);
            } else {
                job.getAltasVueloNoAplicadas().add(info);
                log.warn("Alta de vuelo NO aplicada (job {}, bloque {}): {}",
                        job.getJobId(), bloqueIdx, motivo);
            }
        }
    }

    public int aplicarCancelacionesVuelo(String jobId, java.util.Queue<CancelacionVueloRequest> cola,
                                          Grafo graph,
                                          OperadorReparacionVoraz enrutador, GestorBacklog backlog,
                                          List<VueloCancelado> registro,
                                          List<CancelacionVueloRequest> noAplicadas) {
        if (cola == null || cola.isEmpty() || graph == null || enrutador == null) return 0;
        Map<String, Arista> indiceVuelo = solucionBdReader.construirIndiceVuelo(graph);
        int cancelados = 0;
        List<PersistenciaSolucionService.CancelacionVueloDb> aPersistir = new ArrayList<>();
        CancelacionVueloRequest orden;
        while ((orden = cola.poll()) != null) {
            if (orden.getOrigen() == null || orden.getDestino() == null
                    || orden.getFechaHoraSalida() == null) {
                log.warn("Orden de cancelación de vuelo inválida (campos nulos), ignorada");
                continue;
            }
            String origen = orden.getOrigen().trim();
            String destino = orden.getDestino().trim();
            LocalDateTime dep = orden.getFechaHoraSalida();
            int depMinDia = dep.getHour() * 60 + dep.getMinute();
            long epochDay = dep.toLocalDate().toEpochDay();
            long epochMin = epochDay * CodificadorClaveVuelo.MIN_DIA;

            List<Arista> matches = new ArrayList<>();
            for (Arista e : graph.aristas) {
                if (e.origen != null && e.destino != null
                        && origen.equalsIgnoreCase(e.origen.codigo)
                        && destino.equalsIgnoreCase(e.destino.codigo)
                        && e.minutoDelDiaSalida == depMinDia) {
                    matches.add(e);
                }
            }
            if (matches.isEmpty()) {
                log.warn("Cancelación: no se encontró vuelo {}->{} con salida {} (min-del-día {})",
                        origen, destino, dep, depMinDia);
                if (noAplicadas != null) noAplicadas.add(orden);
                continue;
            }

            List<Arista> edgesCancelados = new ArrayList<>();
            for (Arista e : matches) {
                if (enrutador.agregarVueloCancelado(CodificadorClaveVuelo.claveVuelo(e.indice, epochMin))) {
                    cancelados++;
                    edgesCancelados.add(e);
                }
            }
            if (edgesCancelados.isEmpty()) {
                log.debug("Cancelación duplicada (vuelo-día ya cancelado) {}->{} salida {}: ignorada",
                        origen, destino, dep);
                continue;
            }
            int afectados = reencolarAfectadosPorCancelacion(edgesCancelados, epochDay, backlog, indiceVuelo);
            if (registro != null) {
                registro.add(new VueloCancelado(origen, destino, dep, afectados));
            }
            for (Arista e : edgesCancelados) {
                aPersistir.add(new PersistenciaSolucionService.CancelacionVueloDb(
                        PersistenciaSolucionService.normalizarIdVuelo(e.id), dep.toLocalDate(), afectados));
            }
            log.info("Vuelo cancelado {}->{} salida {} ({} edge-día) — {} envíos devueltos al backlog",
                    origen, destino, dep, edgesCancelados.size(), afectados);
        }
        persistencia.persistirCancelaciones(jobId, aPersistir);
        return cancelados;
    }

    public int aplicarInyeccionesEnvio(EstadoJob job, List<InyeccionEnviosRequest.Item> buffer,
                                        ContextoTemporal ctx, GestorBacklog backlog, Grafo graph) {
        if (job == null || backlog == null) return 0;
        InyeccionEnviosRequest.Item it;
        while ((it = job.getInyeccionesPendientes().poll()) != null) buffer.add(it);
        if (buffer.isEmpty()) return 0;
        int umbralFrag = FragmentadorEnvios.umbralEfectivo(props.getFragmentacion(), graph);
        int maxSublotes = props.getFragmentacion().getMaxSublotes();
        List<EnvioInyectadoInfo> liberados = new ArrayList<>();
        Iterator<InyeccionEnviosRequest.Item> itr = buffer.iterator();
        int n = 0;
        while (itr.hasNext()) {
            InyeccionEnviosRequest.Item x = itr.next();
            LocalDateTime ready = x.getFechaHoraRegistro();
            if (ready != null && !ready.isBefore(ctx.scFin)) continue;
            LocalDateTime readyEff = (ready != null) ? ready : ctx.scInicio;
            Aeropuerto o = cargadorDatos.getAeropuerto(x.getOrigen());
            Aeropuerto d = cargadorDatos.getAeropuerto(x.getDestino());
            if (o == null || d == null) { itr.remove(); continue; }
            int sla = TipoEnvio.derivar(o, d) == TipoEnvio.INTRACONTINENTAL ? 24 : 48;
            String id = "INV-" + ctx.bloqueIdx + "-" + (n++);
            LoteEnvio b = new LoteEnvio(id, x.getCantidad(), sla,
                    o.getCodigo(), d.getCodigo(), readyEff);
            b.setSintetico(true);
            if (x.getClienteId() != null) b.setClienteId(x.getClienteId());
            for (LoteEnvio sub : FragmentadorEnvios.fragmentar(b, umbralFrag, maxSublotes)) {
                backlog.agregarSinRuta(sub);
            }
            EnvioInyectadoInfo info = new EnvioInyectadoInfo(id, o.getCodigo(), d.getCodigo(),
                    x.getCantidad(), x.getClienteId(), sla, readyEff.toString(), ctx.bloqueIdx,
                    x.getRegistrador(), x.getSede());
            job.getEnviosInyectados().add(info);
            liberados.add(info);
            itr.remove();
        }
        if (!liberados.isEmpty()) {
            persistencia.persistirInyecciones(job.getJobId(), liberados);
            log.info("Inyección: {} envío(s) liberado(s) al bloque {} (job {})",
                    liberados.size(), ctx.bloqueIdx, job.getJobId());
        }
        return liberados.size();
    }

    public int reencolarAfectadosPorCancelacion(List<Arista> edgesCancelados, long epochDay,
                                                 GestorBacklog backlog,
                                                 Map<String, Arista> indiceVuelo) {
        if (backlog == null || edgesCancelados == null || edgesCancelados.isEmpty()) return 0;
        List<String> idsVuelo = new ArrayList<>(edgesCancelados.size());
        for (Arista e : edgesCancelados) idsVuelo.add(PersistenciaSolucionService.normalizarIdVuelo(e.id));

        List<LoteEnvio> afectados = solucionBdReader.afectadosPorVuelo(
                idsVuelo, java.time.LocalDate.ofEpochDay(epochDay), indiceVuelo);
        for (LoteEnvio b : afectados) backlog.agregarReplanificable(b);
        return afectados.size();
    }

    public LoteEnvio reenrutarAfectadoDesdePosicion(LoteEnvio b, ContextoTemporal ctx,
            Grafo graph, OperadorReparacionVoraz enrutador,
            Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        if (!b.tienePrefijo()) {
            List<Arista> route = b.getRutaAsignada();
            List<Long> deps  = b.getSalidasAsignadas();
            if (route == null || route.isEmpty() || deps == null || deps.size() != route.size()) {
                enrutador.liberarDeGlobal(b);
                b.limpiarRuta();
                return enrutarSufijo(b, enrutador, blockFlight, blockAirport);
            }
            long ahoraMin = OperadorReparacionVoraz.aMinutoEpochPublico(ctx.scFin);
            int n = route.size();
            int k = n;
            for (int i = 0; i < n; i++) {
                if (deps.get(i) > ahoraMin) { k = i; break; }   // primer tramo PENDIENTE
            }
            if (k == n) {
                return b;
            }
            if (k == 0) {
                enrutador.liberarDeGlobal(b);
                b.limpiarRuta();
                return enrutarSufijo(b, enrutador, blockFlight, blockAirport);
            }
            Arista cortEdge = route.get(k - 1);
            long arrCorte = deps.get(k - 1) + cortEdge.duracionMinutos;
            enrutador.liberarSufijoDeGlobal(b, k);
            b.setPrefijoFijo(new ArrayList<>(route.subList(0, k)));
            b.setPrefijoFijoSalidas(new ArrayList<>(deps.subList(0, k)));
            b.setOrigenActual(cortEdge.destino.codigo);
            b.setTiempoListoActual(epochMinToLdt(arrCorte));
            b.setRutaAsignada(new ArrayList<>());
            b.setSalidasAsignadas(null);
        }
        return enrutarSufijo(b, enrutador, blockFlight, blockAirport);
    }

    public LoteEnvio enrutarSufijo(LoteEnvio b, OperadorReparacionVoraz enrutador,
            Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        LoteEnvio sintetico = new LoteEnvio(b.getId(), b.getCantidad(), b.getHorasLimiteSla(),
                b.origenEfectivo(), b.getCodigoDestino(), b.tiempoListoEfectivo());
        List<RutaCandidata> candidatos = enrutador.generarCandidatosRuta(
                sintetico, blockFlight, blockAirport, SUFIJO_ROUTE_CANDIDATES);
        RutaCandidata elegido = elegirSufijo(candidatos, b, enrutador);
        if (elegido == null) {
            b.setRutaAsignada(new ArrayList<>());
            b.setSalidasAsignadas(null);
            b.setCumpleSLA(false);
            return b;
        }
        enrutador.aplicarCandidatoBloque(sintetico, elegido, blockFlight, blockAirport);
        b.setRutaAsignada(new ArrayList<>(elegido.getAristas()));
        b.setSalidasAsignadas(new ArrayList<>(elegido.getSalidasReales()));
        b.setCumpleSLA(enrutador.cumpleSlaDesdeOrigen(elegido, b));
        return b;
    }

    public RutaCandidata elegirSufijo(
            List<RutaCandidata> candidatos, LoteEnvio original,
            OperadorReparacionVoraz enrutador) {
        if (candidatos == null) return null;
        RutaCandidata mejorOnTime = null, mejorTardio = null;
        for (RutaCandidata c : candidatos) {
            if (enrutador.cumpleSlaDesdeOrigen(c, original)) {
                if (mejorOnTime == null || c.getLlegadaMin() < mejorOnTime.getLlegadaMin()) mejorOnTime = c;
            } else {
                if (mejorTardio == null || c.getLlegadaMin() < mejorTardio.getLlegadaMin()) mejorTardio = c;
            }
        }
        return mejorOnTime != null ? mejorOnTime : mejorTardio;
    }

    public static LocalDateTime epochMinToLdt(long epochMin) {
        long day = Math.floorDiv(epochMin, 1440L);
        int minOfDay = (int) Math.floorMod(epochMin, 1440L);
        return LocalDate.ofEpochDay(day).atTime(minOfDay / 60, minOfDay % 60);
    }

}
