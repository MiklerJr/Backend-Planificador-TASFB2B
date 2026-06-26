package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.CostFunction;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.*;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Vuelo;
import com.tasfb2b.planificador.util.DataLoader;
import com.tasfb2b.planificador.util.SimulacionFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.tasfb2b.planificador.util.SimulacionFormat.porcentaje;
import static com.tasfb2b.planificador.util.SimulacionFormat.safe;
import static com.tasfb2b.planificador.util.SimulacionFormat.vueloFrontId;

/**
 * Read models de SOLO LECTURA sobre los jobs en memoria (Tanda 2A: extraído de
 * {@code PlanificadorService}). Alimenta los endpoints de polling de {@code JobQueryController}
 * (dashboard, indicadores, carga/uso de vuelos, ocupación de almacenes, asignaciones) sin tocar el
 * bucle de simulación ni mutar el job.
 *
 * <p>El JSON de salida es idéntico al que producía {@code PlanificadorService}: estos métodos y sus
 * helpers se movieron tal cual. Los helpers de formato puro compartidos con el orquestador
 * (semáforo, %, {@code safe}, {@code vueloFrontId}, {@code completar*}) viven en
 * {@link SimulacionFormat} para no duplicarlos.
 */
@Service
public class JobQueryService {

    /** Default de filas por página cuando no hay {@link PlanificadorProperties} (constructor de tests). */
    static final int DEFAULT_MAX_FILAS_PAGINA = 5000;

    private final JobsRegistry jobs;
    private final DataLoader dataLoader;
    /** Fase 3 (anti-OOM): reconstruye agregados desde BD cuando se pide histórico fuera de la ventana RAM. */
    private final SolucionBdReader solucionBdReader;
    private final PersistenciaSolucionService persistencia;
    /** Anti-OOM: tope de filas por página de los read models paginados (de {@code planificador.consulta}). */
    private final int maxFilasPagina;

    @Autowired
    public JobQueryService(JobsRegistry jobs, DataLoader dataLoader,
                           SolucionBdReader solucionBdReader, PersistenciaSolucionService persistencia,
                           PlanificadorProperties props) {
        this.jobs = jobs;
        this.dataLoader = dataLoader;
        this.solucionBdReader = solucionBdReader;
        this.persistencia = persistencia;
        this.maxFilasPagina = (props != null && props.getConsulta() != null
                && props.getConsulta().getMaxFilasPagina() > 0)
                ? props.getConsulta().getMaxFilasPagina()
                : DEFAULT_MAX_FILAS_PAGINA;
    }

    /** Constructor sin acceso a BD ni config para tests (BD deshabilitada; usa el default de filas). */
    public JobQueryService(JobsRegistry jobs, DataLoader dataLoader) {
        this(jobs, dataLoader, null, null, null);
    }

    /** Clampea el {@code limit} pedido a {@code [1, maxFilasPagina]}; {@code <=0} ⇒ default configurado. */
    private int limitEfectivo(int limit) {
        if (limit <= 0) return maxFilasPagina;
        return Math.min(limit, maxFilasPagina);
    }

    /** ¿Servir el histórico desde BD? (la ventana RAM ya soltó bloques y la solución del job está en BD). */
    private boolean usarHistoricoBd(JobState job) {
        return job.bloquesPublicados() > job.getMaxBloquesConAsignaciones()
                && solucionBdReader != null && persistencia != null
                && persistencia.reflejaEnBd(job.getJobId());
    }

    private JobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Read model liviano para dashboard operativo. No modifica el job ni fuerza
     * recalculos del motor; usa el resultado final si existe o los bloques ya
     * publicados si el job sigue corriendo.
     */
    public DashboardResponse getDashboardJob(String jobId) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        // Fase 5b-2: con el job en curso usa el snapshot de métricas (las asignaciones de bloques
        // viejos se purgan, así que no se pueden recontar desde bloquesDesde(0)).
        Metricas metricas = job.resultado != null
                ? job.resultado.getMetricas()
                : (job.metricasSnapshot != null ? job.metricasSnapshot
                                                : metricasDesdeBloques(job.bloquesDesde(0)));

        DashboardResponse body = new DashboardResponse();
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

    /**
     * Snapshot del semáforo "ahora": umbrales + carga de vuelos y ocupación de almacenes de los bloques
     * MÁS RECIENTES de la ventana RAM. Anti-OOM: acotado de forma dura a {@link #maxFilasPagina} filas
     * por sección (no es un volcado histórico) y tomando el tail (lo reciente), no el frente (lo viejo).
     * Para el histórico completo el front pagina {@code /vuelos/carga} y {@code /almacenes/ocupacion}.
     */
    public IndicadoresResponse getIndicadoresJob(String jobId) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        IndicadoresResponse body = new IndicadoresResponse();
        body.setJobId(jobId);
        body.setUmbrales(new IndicadoresResponse.Umbrales(
                CostFunction.UMBRAL_VERDE, CostFunction.UMBRAL_AMBAR));
        body.setVuelos(cargaVuelosRecientes(job, maxFilasPagina));
        body.setAlmacenes(ocupacionRecientes(job, maxFilasPagina));
        return body;
    }

    /**
     * Carga de vuelos de los bloques MÁS RECIENTES de la ventana RAM, acotada a {@code limit} filas
     * (snapshot del semáforo). Recorre la ventana desde el final e incluye bloques COMPLETOS hasta
     * alcanzar {@code limit}, devolviéndolos en orden cronológico. Memoria O(limit + 1 bloque).
     */
    private List<CargaVueloRow> cargaVuelosRecientes(JobState job, int limit) {
        List<BloqueSimulacion> ventana = job.bloquesDesde(0);
        List<CargaVueloRow> acc = new ArrayList<>();
        for (int i = ventana.size() - 1; i >= 0; i--) {
            if (!acc.isEmpty() && acc.size() >= limit) break;
            BloqueSimulacion bloque = ventana.get(i);
            List<CargaVueloRow> filas = new ArrayList<>();
            for (CargaVuelo carga : cargasDelBloque(bloque)) filas.add(cargaVueloRow(carga, bloque));
            acc.addAll(0, filas);   // prepende para mantener el orden cronológico
        }
        return acc;
    }

    /** Análogo a {@link #cargaVuelosRecientes} para la ocupación de almacenes (tail reciente, acotado). */
    private List<OcupacionAlmacenRow> ocupacionRecientes(JobState job, int limit) {
        List<BloqueSimulacion> ventana = job.bloquesDesde(0);
        List<OcupacionAlmacenRow> acc = new ArrayList<>();
        for (int i = ventana.size() - 1; i >= 0; i--) {
            if (!acc.isEmpty() && acc.size() >= limit) break;
            BloqueSimulacion bloque = ventana.get(i);
            List<OcupacionAlmacenRow> filas = new ArrayList<>();
            for (OcupacionAlmacen oc : ocupacionesDelBloque(bloque)) filas.add(ocupacionAlmacenRow(oc, bloque));
            acc.addAll(0, filas);
        }
        return acc;
    }

    /**
     * Carga de vuelos por bloque, PAGINADA (anti-OOM). {@code desde} = cursor de reanudación opaco
     * ({@code 0} para empezar); {@code limit} = tope de filas (clampeado a {@link #maxFilasPagina}).
     * El front recorre páginas reusando {@code proximoDesde} mientras {@code hayMas}; para refrescar,
     * reinicia en {@code desde=0}. El cursor es válido DENTRO de un mismo recorrido.
     */
    public CargaVuelosResponse getCargaVuelosJob(String jobId, int desde, int limit) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        int desdeNorm = Math.max(0, desde);
        int limitEf = limitEfectivo(limit);

        List<CargaVueloRow> vuelos;
        int proximoDesde;
        boolean hayMas;
        // Fase 3 (anti-OOM): si el buffer deslizante ya soltó bloques (hay histórico fuera de RAM) y el
        // job tiene su solución en BD, se pagina el histórico desde BD; si no, la ventana RAM.
        if (usarHistoricoBd(job)) {
            // Ruta BD: cursor = filas (OFFSET/LIMIT). Pide limit+1 para detectar si quedan más páginas.
            List<CargaVueloRow> pagina = solucionBdReader.reconstruirCargasVuelos(desdeNorm, limitEf + 1);
            hayMas = pagina.size() > limitEf;
            vuelos = hayMas ? new ArrayList<>(pagina.subList(0, limitEf)) : pagina;
            proximoDesde = desdeNorm + vuelos.size();
        } else {
            // Ruta RAM: cursor = bloque. Incluye bloques COMPLETOS desde `desde` hasta acumular ≥ limit
            // filas (no parte las filas de un bloque entre páginas).
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
        body.setTerminado(!JobsRegistry.ESTADOS_ACTIVOS.contains(job.estado));
        body.setTotal(vuelos.size());
        body.setVuelos(vuelos);
        return body;
    }

    /**
     * Vuelos efectivamente usados por las asignaciones publicadas desde el bloque {@code desde}.
     * Eje temporal: {@code flightKey}, {@code fechaSalida} y {@code fechaLlegada} están en UTC
     * (mismo eje que {@code TramoRuta.salidaUtc} y {@code CargaVuelo.fechaSalida}), de modo que
     * el front puede animar el vuelo-día sobre un reloj global sin mezclar husos.
     */
    public VuelosUsadosResponse getVuelosUsadosJob(String jobId, int desde) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        int desdeNormalizado = Math.max(0, desde);
        // Fase 5b-2: el agregado vive en el JobState (acumulado por bloque), no se reconstruye desde
        // las asignaciones (que se purgan de los bloques viejos).
        // Fase 3 (anti-OOM): si se pide histórico FUERA de la ventana reciente en RAM y el job tiene su
        // solución en BD, se reconstruye el histórico COMPLETO desde BD; si no, se sirve la ventana RAM.
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
        response.setTerminado(!JobsRegistry.ESTADOS_ACTIVOS.contains(job.estado));
        response.setTotal(vuelos.size());
        response.setVuelos(vuelos);
        return response;
    }

    /**
     * Ocupación de almacenes por bloque, PAGINADA (anti-OOM; mismo contrato de cursor que
     * {@link #getCargaVuelosJob}). La ocupación concurrente por slot NO se deriva directo de BD ⇒
     * siempre se sirve desde la VENTANA reciente en RAM (cursor = bloque). El front pagina con
     * {@code desde}/{@code proximoDesde} mientras {@code hayMas}.
     */
    public OcupacionAlmacenesResponse getOcupacionAlmacenesJob(String jobId, int desde, int limit) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        int desdeNorm = Math.max(0, desde);
        int limitEf = limitEfectivo(limit);

        // Ruta RAM (única): incluye bloques COMPLETOS desde `desde` hasta acumular ≥ limit filas.
        List<OcupacionAlmacenRow> almacenes = new ArrayList<>();
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
        body.setTerminado(!JobsRegistry.ESTADOS_ACTIVOS.contains(job.estado));
        body.setTotal(almacenes.size());
        body.setAlmacenes(almacenes);
        return body;
    }

    public AsignacionesResponse getAsignacionesJob(String jobId, int desde,
                                                  String aeropuerto,
                                                  String vueloId,
                                                  boolean soloEnrutadas) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        String aeropuertoNorm = normalizarCodigo(aeropuerto);
        String vueloNorm = normalizarTexto(vueloId);
        List<AsignacionesResponse.AsignacionItem> asignaciones = new ArrayList<>();

        for (BloqueSimulacion bloque : job.bloquesDesde(desde)) {
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
        body.setAsignaciones(asignaciones);
        return body;
    }

    // =========================================================
    // Helpers privados (movidos tal cual desde PlanificadorService)
    // =========================================================

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

    private static DashboardResponse.Tasas tasas(Metricas m) {
        DashboardResponse.Tasas tasas = new DashboardResponse.Tasas();
        int procesadas = m != null ? m.getProcesadas() : 0;
        tasas.setEnrutamientoPct(porcentaje(m != null ? m.getEnrutadas() : 0, procesadas));
        tasas.setSinRutaPct(porcentaje(m != null ? m.getSinRuta() : 0, procesadas));
        tasas.setCumpleSlaPct(porcentaje(m != null ? m.getCumpleSLA() : 0, procesadas));
        tasas.setTardadasPct(porcentaje(m != null ? m.getTardadas() : 0, procesadas));
        return tasas;
    }

    private static DashboardResponse.UltimoBloque ultimoBloqueResumen(JobState job) {
        if (job == null || job.bloquesPublicados() == 0) return null;
        List<BloqueSimulacion> ultimos =
                job.bloquesDesde(job.bloquesPublicados() - 1);
        if (ultimos.isEmpty()) return null;
        BloqueSimulacion b = ultimos.get(0);
        DashboardResponse.UltimoBloque out = new DashboardResponse.UltimoBloque();
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
                // Eje UTC, igual que buildCargasVuelos: el mismo campo no puede cambiar de eje
                // según el camino (principal vs fallback legacy).
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

        acc.values().forEach(SimulacionFormat::completarCargaVuelo);
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
            // Eje UTC: el camino principal (buildOcupacionAlmacenes) deriva la fecha del
            // almacén-día del slot UTC; el fallback debe usar el mismo eje.
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

        acc.values().forEach(SimulacionFormat::completarOcupacionAlmacen);
        return new ArrayList<>(acc.values());
    }

    private Map<String, Integer> capacidadesVuelosPorId() {
        Map<String, Integer> out = new HashMap<>();
        for (Vuelo vuelo : dataLoader.getVuelos()) {
            out.put(vueloFrontId(vuelo), vuelo.getCapacidad() != null ? vuelo.getCapacidad() : 0);
        }
        return out;
    }

    private Map<String, Integer> capacidadesAlmacenPorCodigo() {
        Map<String, Integer> out = new HashMap<>();
        for (Aeropuerto aeropuerto : dataLoader.getAeropuertos()) {
            out.put(aeropuerto.getCodigo(), aeropuerto.getCapacidad() != null ? aeropuerto.getCapacidad() : 0);
        }
        return out;
    }

    private static CargaVueloRow cargaVueloRow(CargaVuelo c, BloqueSimulacion bloque) {
        CargaVueloRow row = new CargaVueloRow();
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

    private static OcupacionAlmacenRow ocupacionAlmacenRow(OcupacionAlmacen o, BloqueSimulacion bloque) {
        OcupacionAlmacenRow row = new OcupacionAlmacenRow();
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
