package com.tasfb2b.planificador.servicios.jobs;

import com.tasfb2b.planificador.algoritmo.aco.ConstantesOperativas;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.dto.almacenes.*;
import com.tasfb2b.planificador.dto.jobs.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.servicios.persistencia.PersistenciaSolucionService;
import com.tasfb2b.planificador.servicios.persistencia.LectorSolucionBd;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.FormatoSimulacion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.tasfb2b.planificador.utilidades.FormatoSimulacion.porcentaje;
import static com.tasfb2b.planificador.utilidades.FormatoSimulacion.safe;
import static com.tasfb2b.planificador.utilidades.FormatoSimulacion.vueloFrontId;


@Service
public class ConsultaJobsService {

    static final int DEFAULT_MAX_FILAS_PAGINA = 5000;

    private final RegistroJobs jobs;
    private final CargadorDatos cargadorDatos;
    private final LectorSolucionBd solucionBdReader;
    private final PersistenciaSolucionService persistencia;
    private final int maxFilasPagina;

    @Autowired
    public ConsultaJobsService(RegistroJobs jobs, CargadorDatos cargadorDatos,
                           LectorSolucionBd solucionBdReader, PersistenciaSolucionService persistencia,
                           PlanificadorProperties props) {
        this.jobs = jobs;
        this.cargadorDatos = cargadorDatos;
        this.solucionBdReader = solucionBdReader;
        this.persistencia = persistencia;
        this.maxFilasPagina = (props != null && props.getConsulta() != null
                && props.getConsulta().getMaxFilasPagina() > 0)
                ? props.getConsulta().getMaxFilasPagina()
                : DEFAULT_MAX_FILAS_PAGINA;
    }

    public ConsultaJobsService(RegistroJobs jobs, CargadorDatos cargadorDatos) {
        this(jobs, cargadorDatos, null, null, null);
    }

    private int limitEfectivo(int limit) {
        if (limit <= 0) return maxFilasPagina;
        return Math.min(limit, maxFilasPagina);
    }

    private boolean usarHistoricoBd(EstadoJob job) {
        return job.bloquesPublicados() > job.getMaxBloquesConAsignaciones()
                && solucionBdReader != null && persistencia != null
                && persistencia.reflejaEnBd(job.getJobId());
    }

    private EstadoJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    /** Reenganche: el job en curso (a lo sumo uno ejecuta a la vez) o, si solo hay
     *  encolados, el más antiguo (el próximo a ejecutar). Sin jobs activos ⇒ activo=false. */
    public JobActivoResponse getJobActivo() {
        JobActivoResponse body = new JobActivoResponse();
        List<EstadoJob> activos = jobs.listarActivos();   // ordenados por inicio ascendente
        EstadoJob elegido = null;
        for (EstadoJob j : activos) {
            if ("ejecutando".equals(j.estado) || "calentando".equals(j.estado)) {
                elegido = j;
                break;
            }
        }
        if (elegido == null && !activos.isEmpty()) elegido = activos.get(0);
        if (elegido == null) {
            body.setActivo(false);
            return body;
        }
        body.setActivo(true);
        body.setJobId(elegido.getJobId());
        body.setEscenario(elegido.getEscenario());
        body.setAlgoritmo(elegido.algoritmo);
        body.setEstado(elegido.estado);
        body.setEnVivo(elegido.enVivo);
        body.setProgreso(elegido.getProgreso());
        body.setTotalBloques(elegido.bloquesPublicados());
        body.setPrimerBloqueDisponible(elegido.primerBloqueDisponible());
        if (elegido.primerBloqueRealMs != null) {
            body.setTemporizadorInicioUtc(java.time.Instant.ofEpochMilli(elegido.primerBloqueRealMs).toString());
        }
        body.setDuracionRealMs(elegido.getDuracionRealMs());
        return body;
    }

    public TableroResponse getTableroJob(String jobId) {
        EstadoJob job = getJob(jobId);
        if (job == null) return null;

        Metricas metricas = job.resultado != null
                ? job.resultado.getMetricas()
                : (job.metricasSnapshot != null ? job.metricasSnapshot
                                                : metricasDesdeBloques(job.bloquesDesde(0)));

        TableroResponse body = new TableroResponse();
        body.setJobId(job.getJobId());
        body.setEscenario(job.getEscenario());
        body.setAlgoritmo(job.algoritmo);
        body.setEstado(job.estado);
        body.setK(job.getK());
        body.setSeed(job.seed);
        if (job.fechaInicio != null) body.setFechaInicio(job.fechaInicio.toString());
        body.setInicio(job.inicio.toString());
        if (job.fin != null) body.setFin(job.fin.toString());
        body.setProgreso(job.getProgreso());
        body.setProgresoWarmup(job.getProgresoWarmup());
        body.setBloqueActual(job.bloqueActual);
        body.setTotalBloques(job.totalBloques);
        body.setBloquesPublicados(job.bloquesPublicados());
        body.setPosicionEnCola(jobs.posicionEnCola(jobId));
        body.setCanceladoPorUsuario(job.canceladoPorUsuario);
        if (job.error != null) body.setError(job.error);
        body.setMetricas(metricas);
        body.setTasas(tasas(metricas));
        body.setUltimoBloque(ultimoBloqueResumen(job));
        return body;
    }

    public IndicadoresResponse getIndicadoresJob(String jobId) {
        EstadoJob job = getJob(jobId);
        if (job == null) return null;

        IndicadoresResponse body = new IndicadoresResponse();
        body.setJobId(jobId);
        body.setUmbrales(new IndicadoresResponse.Umbrales(
                ConstantesOperativas.UMBRAL_VERDE, ConstantesOperativas.UMBRAL_AMBAR));
        body.setVuelos(cargaVuelosRecientes(job, maxFilasPagina));
        body.setAlmacenes(ocupacionRecientes(job, maxFilasPagina));
        return body;
    }

    private List<CargaVueloFila> cargaVuelosRecientes(EstadoJob job, int limit) {
        List<BloqueSimulacion> ventana = job.bloquesDesde(0);
        List<CargaVueloFila> acc = new ArrayList<>();
        for (int i = ventana.size() - 1; i >= 0; i--) {
            if (!acc.isEmpty() && acc.size() >= limit) break;
            BloqueSimulacion bloque = ventana.get(i);
            List<CargaVueloFila> filas = new ArrayList<>();
            for (CargaVuelo carga : cargasDelBloque(bloque)) filas.add(cargaVueloRow(carga, bloque));
            acc.addAll(0, filas);   // prepende para mantener el orden cronológico
        }
        return acc;
    }

    private List<OcupacionAlmacenFila> ocupacionRecientes(EstadoJob job, int limit) {
        List<BloqueSimulacion> ventana = job.bloquesDesde(0);
        List<OcupacionAlmacenFila> acc = new ArrayList<>();
        for (int i = ventana.size() - 1; i >= 0; i--) {
            if (!acc.isEmpty() && acc.size() >= limit) break;
            BloqueSimulacion bloque = ventana.get(i);
            List<OcupacionAlmacenFila> filas = new ArrayList<>();
            for (OcupacionAlmacen oc : ocupacionesDelBloque(bloque)) filas.add(ocupacionAlmacenRow(oc, bloque));
            acc.addAll(0, filas);
        }
        return acc;
    }

    public CargaVuelosResponse getCargaVuelosJob(String jobId, int desde, int limit) {
        EstadoJob job = getJob(jobId);
        if (job == null) return null;

        int desdeNorm = Math.max(0, desde);
        int limitEf = limitEfectivo(limit);

        List<CargaVueloFila> vuelos;
        int proximoDesde;
        boolean hayMas;
        if (usarHistoricoBd(job)) {
            List<CargaVueloFila> pagina = solucionBdReader.reconstruirCargasVuelos(desdeNorm, limitEf + 1);
            hayMas = pagina.size() > limitEf;
            vuelos = hayMas ? new ArrayList<>(pagina.subList(0, limitEf)) : pagina;
            proximoDesde = desdeNorm + vuelos.size();
        } else {
            vuelos = new ArrayList<>();
            proximoDesde = desdeNorm;
            hayMas = false;
            for (BloqueSimulacion bloque : job.bloquesDesde(desdeNorm)) {
                if (!vuelos.isEmpty() && vuelos.size() >= limitEf) { hayMas = true; break; }
                for (CargaVuelo carga : cargasDelBloque(bloque)) {
                    vuelos.add(cargaVueloRow(carga, bloque));
                }
                proximoDesde = bloque.getBloqueIdx() + 1;
            }
        }

        CargaVuelosResponse body = new CargaVuelosResponse();
        body.setJobId(jobId);
        body.setDesde(desdeNorm);
        body.setProximoDesde(proximoDesde);
        body.setHayMas(hayMas);
        body.setBloquesPublicados(job.bloquesPublicados());
        body.setTerminado(!RegistroJobs.ESTADOS_ACTIVOS.contains(job.estado));
        body.setTotal(vuelos.size());
        body.setVuelos(vuelos);
        return body;
    }

    public VuelosUsadosResponse getVuelosUsadosJob(String jobId, int desde) {
        EstadoJob job = getJob(jobId);
        if (job == null) return null;

        int desdeNormalizado = Math.max(0, desde);
        int corte = Math.max(0, job.bloquesPublicados() - job.getMaxBloquesConAsignaciones());
        List<VuelosUsadosResponse.VueloUsado> vuelos;
        if (desdeNormalizado < corte && solucionBdReader != null
                && persistencia != null && persistencia.reflejaEnBd(jobId)) {
            vuelos = solucionBdReader.reconstruirVuelosUsados();
        } else {
            vuelos = job.vuelosUsadosDesde(desdeNormalizado);
        }

        VuelosUsadosResponse response = new VuelosUsadosResponse();
        response.setJobId(jobId);
        response.setDesde(desdeNormalizado);
        response.setBloquesPublicados(job.bloquesPublicados());
        response.setTerminado(!RegistroJobs.ESTADOS_ACTIVOS.contains(job.estado));
        response.setTotal(vuelos.size());
        response.setVuelos(vuelos);
        return response;
    }

    public OcupacionAlmacenesResponse getOcupacionAlmacenesJob(String jobId, int desde, int limit) {
        EstadoJob job = getJob(jobId);
        if (job == null) return null;

        int desdeNorm = Math.max(0, desde);
        int limitEf = limitEfectivo(limit);

        List<OcupacionAlmacenFila> almacenes = new ArrayList<>();
        int proximoDesde = desdeNorm;
        boolean hayMas = false;
        for (BloqueSimulacion bloque : job.bloquesDesde(desdeNorm)) {
            if (!almacenes.isEmpty() && almacenes.size() >= limitEf) { hayMas = true; break; }
            for (OcupacionAlmacen ocupacion : ocupacionesDelBloque(bloque)) {
                almacenes.add(ocupacionAlmacenRow(ocupacion, bloque));
            }
            proximoDesde = bloque.getBloqueIdx() + 1;
        }

        OcupacionAlmacenesResponse body = new OcupacionAlmacenesResponse();
        body.setJobId(jobId);
        body.setDesde(desdeNorm);
        body.setProximoDesde(proximoDesde);
        body.setHayMas(hayMas);
        body.setBloquesPublicados(job.bloquesPublicados());
        body.setTerminado(!RegistroJobs.ESTADOS_ACTIVOS.contains(job.estado));
        body.setTotal(almacenes.size());
        body.setAlmacenes(almacenes);
        return body;
    }

    public AsignacionesResponse getAsignacionesJob(String jobId, int desde,
                                                  String aeropuerto,
                                                  String vueloId,
                                                  boolean soloEnrutadas) {
        EstadoJob job = getJob(jobId);
        if (job == null) return null;

        String aeropuertoNorm = normalizarCodigo(aeropuerto);
        String vueloNorm = normalizarTexto(vueloId);
        List<AsignacionesResponse.AsignacionItem> asignaciones = new ArrayList<>();

        for (BloqueSimulacion bloque : job.bloquesDesdeExacto(desde)) {   // desde purgado ⇒ vacío
            if (bloque.getAsignaciones() == null) continue;
            for (AsignacionMaleta asignacion : bloque.getAsignaciones()) {
                if (soloEnrutadas && !asignacion.isEnrutada()) continue;
                if (aeropuertoNorm != null && !pasaFiltroAeropuerto(asignacion, aeropuertoNorm)) continue;
                if (vueloNorm != null && !pasaFiltroVuelo(asignacion, vueloNorm)) continue;

                AsignacionesResponse.AsignacionItem item = new AsignacionesResponse.AsignacionItem();
                item.setBloqueIdx(bloque.getBloqueIdx());
                item.setHoraInicio(bloque.getHoraInicio());
                item.setHoraFin(bloque.getHoraFin());
                item.setAsignacion(asignacion);
                asignaciones.add(item);
            }
        }

        AsignacionesResponse body = new AsignacionesResponse();
        body.setJobId(jobId);
        body.setDesde(Math.max(0, desde));
        body.setAeropuerto(aeropuertoNorm);
        body.setVueloId(vueloNorm);
        body.setSoloEnrutadas(soloEnrutadas);
        body.setTotal(asignaciones.size());
        body.setPrimerBloqueDisponible(job.primerBloqueDisponible());
        body.setAsignaciones(asignaciones);
        return body;
    }

    // Helpers privados

    private Metricas metricasDesdeBloques(List<BloqueSimulacion> bloques) {
        Metricas m = new Metricas();
        if (bloques == null || bloques.isEmpty()) return m;

        int procesadas = 0;
        int enrutadas = 0;
        int cumpleSla = 0;
        long maletas = 0L;
        long taMin = Long.MAX_VALUE;
        long taMax = 0L;
        long taTotal = 0L;
        int taCount = 0;

        for (BloqueSimulacion bloque : bloques) {
            procesadas += bloque.getMaletasProcesadas();
            enrutadas += bloque.getMaletasEnrutadas();
            if (bloque.getAsignaciones() != null) {
                for (AsignacionMaleta a : bloque.getAsignaciones()) {
                    maletas += a.getCantidad();
                    if (a.isEnrutada() && a.isCumpleSLA()) cumpleSla++;
                }
            }
            if (bloque.getTaMs() > 0) {
                taMin = Math.min(taMin, bloque.getTaMs());
                taMax = Math.max(taMax, bloque.getTaMs());
                taTotal += bloque.getTaMs();
                taCount++;
            }
        }

        m.setProcesadas(procesadas);
        m.setEnrutadas(enrutadas);
        m.setSinRuta(Math.max(0, procesadas - enrutadas));
        m.setCumpleSLA(cumpleSla);
        m.setTardadas(enrutadas - cumpleSla);
        m.setMaletasIndividuales(maletas);
        if (taCount > 0) {
            m.setTaMinMs(taMin);
            m.setTaMaxMs(taMax);
            m.setTaPromedioMs(taTotal / taCount);
            m.setTiempoTotalAlgMs(taTotal);
        }
        return m;
    }

    private static TableroResponse.Tasas tasas(Metricas m) {
        TableroResponse.Tasas tasas = new TableroResponse.Tasas();
        int procesadas = m != null ? m.getProcesadas() : 0;
        tasas.setEnrutamientoPct(porcentaje(m != null ? m.getEnrutadas() : 0, procesadas));
        tasas.setSinRutaPct(porcentaje(m != null ? m.getSinRuta() : 0, procesadas));
        tasas.setCumpleSlaPct(porcentaje(m != null ? m.getCumpleSLA() : 0, procesadas));
        tasas.setTardadasPct(porcentaje(m != null ? m.getTardadas() : 0, procesadas));
        return tasas;
    }

    private static TableroResponse.UltimoBloque ultimoBloqueResumen(EstadoJob job) {
        if (job == null || job.bloquesPublicados() == 0) return null;
        List<BloqueSimulacion> ultimos =
                job.bloquesDesde(job.bloquesPublicados() - 1);
        if (ultimos.isEmpty()) return null;
        BloqueSimulacion b = ultimos.get(0);
        TableroResponse.UltimoBloque out = new TableroResponse.UltimoBloque();
        out.setBloqueIdx(b.getBloqueIdx());
        out.setHoraInicio(b.getHoraInicio());
        out.setHoraFin(b.getHoraFin());
        out.setMaletasProcesadas(b.getMaletasProcesadas());
        out.setMaletasEnrutadas(b.getMaletasEnrutadas());
        out.setMaletasProcesadasAcum(b.getMaletasProcesadasAcum());
        out.setMaletasEnrutadasAcum(b.getMaletasEnrutadasAcum());
        out.setMaletasEntregadasAcum(b.getMaletasEntregadasAcum());
        out.setTaMs(b.getTaMs());
        out.setScMinutos(b.getScMinutos());
        return out;
    }

    private List<CargaVuelo> cargasDelBloque(BloqueSimulacion bloque) {
        if (bloque == null) return List.of();
        if (bloque.getCargasVuelos() != null && !bloque.getCargasVuelos().isEmpty()) {
            return bloque.getCargasVuelos();
        }
        return derivarCargasDesdeAsignaciones(bloque);
    }

    private List<OcupacionAlmacen> ocupacionesDelBloque(BloqueSimulacion bloque) {
        if (bloque == null) return List.of();
        if (bloque.getOcupacionAlmacenes() != null && !bloque.getOcupacionAlmacenes().isEmpty()) {
            return bloque.getOcupacionAlmacenes();
        }
        return derivarOcupacionesDesdeAsignaciones(bloque);
    }

    private List<CargaVuelo> derivarCargasDesdeAsignaciones(
            BloqueSimulacion bloque) {
        if (bloque == null || bloque.getAsignaciones() == null) return List.of();
        Map<String, Integer> capacidades = capacidadesVuelosPorId();
        Map<String, CargaVuelo> acc = new LinkedHashMap<>();

        for (AsignacionMaleta asignacion : bloque.getAsignaciones()) {
            if (!asignacion.isEnrutada() || asignacion.getTramos() == null) continue;
            for (TramoRuta tramo : asignacion.getTramos()) {
                String vueloId = safe(tramo.getVueloId());
                String salida = safe(tramo.getSalidaUtc());
                String key = vueloId + "|" + salida;
                CargaVuelo dto = acc.computeIfAbsent(key, k -> {
                    CargaVuelo nuevo = new CargaVuelo();
                    nuevo.setVueloId(vueloId);
                    nuevo.setOrigen(safe(tramo.getOrigen()));
                    nuevo.setDestino(safe(tramo.getDestino()));
                    nuevo.setFechaSalida(salida);
                    nuevo.setFechaLlegada(safe(tramo.getLlegadaUtc()));
                    nuevo.setCapacidadMaxima(capacidades.getOrDefault(vueloId, 0));
                    return nuevo;
                });
                dto.setCargaAsignada(dto.getCargaAsignada() + asignacion.getCantidad());
            }
        }

        acc.values().forEach(FormatoSimulacion::completarCargaVuelo);
        return new ArrayList<>(acc.values());
    }

    private List<OcupacionAlmacen> derivarOcupacionesDesdeAsignaciones(
            BloqueSimulacion bloque) {
        if (bloque == null || bloque.getAsignaciones() == null) return List.of();
        Map<String, Integer> capacidades = capacidadesAlmacenPorCodigo();
        Map<String, OcupacionAlmacen> acc = new LinkedHashMap<>();

        for (AsignacionMaleta asignacion : bloque.getAsignaciones()) {
            if (!asignacion.isEnrutada()
                    || asignacion.getTramos() == null
                    || asignacion.getTramos().isEmpty()) continue;

            TramoRuta ultimo =
                    asignacion.getTramos().get(asignacion.getTramos().size() - 1);
            String aeropuerto = safe(ultimo.getDestino());
            String fecha = fechaDe(ultimo.getLlegadaUtc());
            String key = aeropuerto + "|" + fecha;
            OcupacionAlmacen dto = acc.computeIfAbsent(key, k -> {
                OcupacionAlmacen nuevo = new OcupacionAlmacen();
                nuevo.setAeropuerto(aeropuerto);
                nuevo.setFecha(fecha);
                nuevo.setCapacidadMaxima(capacidades.getOrDefault(aeropuerto, 0));
                return nuevo;
            });
            dto.setOcupacionAsignada(dto.getOcupacionAsignada() + asignacion.getCantidad());
        }

        acc.values().forEach(FormatoSimulacion::completarOcupacionAlmacen);
        return new ArrayList<>(acc.values());
    }

    private Map<String, Integer> capacidadesVuelosPorId() {
        Map<String, Integer> out = new HashMap<>();
        for (Vuelo vuelo : cargadorDatos.getVuelos()) {
            out.put(vueloFrontId(vuelo), vuelo.getCapacidad() != null ? vuelo.getCapacidad() : 0);
        }
        return out;
    }

    private Map<String, Integer> capacidadesAlmacenPorCodigo() {
        Map<String, Integer> out = new HashMap<>();
        for (Aeropuerto aeropuerto : cargadorDatos.getAeropuertos()) {
            out.put(aeropuerto.getCodigo(), aeropuerto.getCapacidad() != null ? aeropuerto.getCapacidad() : 0);
        }
        return out;
    }

    private static CargaVueloFila cargaVueloRow(CargaVuelo c, BloqueSimulacion bloque) {
        CargaVueloFila row = new CargaVueloFila();
        row.setVueloId(c.getVueloId());
        row.setOrigen(c.getOrigen());
        row.setDestino(c.getDestino());
        row.setFechaSalida(c.getFechaSalida());
        row.setFechaLlegada(c.getFechaLlegada());
        row.setCapacidadMaxima(c.getCapacidadMaxima());
        row.setCargaAsignada(c.getCargaAsignada());
        row.setPorcentajeCarga(c.getPorcentajeCarga());
        row.setSemaforo(c.getSemaforo());
        row.setBloqueIdx(bloque.getBloqueIdx());
        row.setHoraInicio(bloque.getHoraInicio());
        row.setHoraFin(bloque.getHoraFin());
        return row;
    }

    private static OcupacionAlmacenFila ocupacionAlmacenRow(OcupacionAlmacen o, BloqueSimulacion bloque) {
        OcupacionAlmacenFila row = new OcupacionAlmacenFila();
        row.setAeropuerto(o.getAeropuerto());
        row.setFecha(o.getFecha());
        row.setCapacidadMaxima(o.getCapacidadMaxima());
        row.setOcupacionAsignada(o.getOcupacionAsignada());
        row.setPorcentajeOcupacion(o.getPorcentajeOcupacion());
        row.setSemaforo(o.getSemaforo());
        row.setBloqueIdx(bloque.getBloqueIdx());
        row.setHoraInicio(bloque.getHoraInicio());
        row.setHoraFin(bloque.getHoraFin());
        return row;
    }

    private static boolean pasaFiltroAeropuerto(AsignacionMaleta a, String aeropuerto) {
        if (aeropuerto.equalsIgnoreCase(safe(a.getOrigen()))
                || aeropuerto.equalsIgnoreCase(safe(a.getDestino()))) return true;
        if (a.getTramos() == null) return false;
        for (TramoRuta tramo : a.getTramos()) {
            if (aeropuerto.equalsIgnoreCase(safe(tramo.getOrigen()))
                    || aeropuerto.equalsIgnoreCase(safe(tramo.getDestino()))) return true;
        }
        return false;
    }

    private static boolean pasaFiltroVuelo(AsignacionMaleta a, String vueloId) {
        if (a.getTramos() == null) return false;
        for (TramoRuta tramo : a.getTramos()) {
            if (vueloId.equalsIgnoreCase(safe(tramo.getVueloId()))) return true;
        }
        return false;
    }

    private static String normalizarCodigo(String value) {
        String text = normalizarTexto(value);
        return text != null ? text.toUpperCase() : null;
    }

    private static String normalizarTexto(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static String fechaDe(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) return "";
        int t = isoDateTime.indexOf('T');
        return t > 0 ? isoDateTime.substring(0, t) : isoDateTime;
    }

}
