package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.algorithm.alns.*;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.AlertaColapso;
import com.tasfb2b.planificador.dto.AuditoriaEnvio;
import com.tasfb2b.planificador.dto.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.EjecucionParams;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.dto.PlanificacionResultado;
import com.tasfb2b.planificador.dto.ResumenPlanificacionGlobal;
import com.tasfb2b.planificador.dto.VueloCancelado;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.Vuelo;
import com.tasfb2b.planificador.dto.VuelosUsadosResponse;
import com.tasfb2b.planificador.util.AlgorithmMapper;
import com.tasfb2b.planificador.util.DataLoader;
import com.tasfb2b.planificador.util.FlightParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class PlanificadorService {

    // ── DEPENDENCIAS NUEVAS (ALNS Y ESCENARIOS) ─────────────────────────
    private final DataLoader dataLoader;
    private final AlgorithmMapper mapper;
    private final PlanificadorProperties props;
    private final JobsRegistry jobs;
    private final AuditoriaService auditoria;
    private final AcoBlockEngine acoEngine;

    public static final String MOTOR_ALNS = "alns";
    public static final String MOTOR_ACO  = "aco";

    /** Fase T (N3): candidatos por clave al pre-calentar esqueletos (= GROUP_ROUTE_CANDIDATES del hot-path). */
    private static final int PREWARM_ROUTE_CANDIDATES = 5;

    // ── DEPENDENCIAS ANTIGUAS (ACO) ─────────────────────────────────────
    private final AeropuertoLoader aeropuertoLoader;
    private final GraphBuilder graphBuilder;
    private final EnvioLoader envioLoader;

    // ── CONSTANTES ACO ──────────────────────────────────────────────────
    private static final int DEFAULT_TICK_MINUTES = 5;
    private static final String[] ORIGENES_DISPONIBLES = {
            "SKBO", "SEQM", "SVMI", "SBBR", "SPIM", "SLLP", "SCEL", "SABE",
            "SGAS", "SUAA", "LATI", "EDDI", "LOWW", "EBCI", "UMMS", "LBSF",
            "LKPR", "LDZA", "EKCH", "EHAM", "VIDP", "OSDI", "OERK", "OMDB",
            "OAKB", "OOMS", "OYSN", "OPKC", "OJAI", "UBBB"
    };

    // Cache perezosa código ICAO → offset horario (GMT), para reconstruir UTC real en el DTO.
    private volatile Map<String, Integer> offsetPorCodigo = null;

    // ── ESTADO ESCENARIOS ALNS ──────────────────────────────────────────
    private volatile List<SimulacionResponse.BloqueSimulacion> bloquesCacheados = null;
    private volatile Graph sc1Graph = null;
    private volatile GreedyRepairOperator sc1Enrutador = null;
    private volatile AlnsSolution sc1Dummy = null;
    private volatile List<TemporalContext> sc1Plan = null;
    private volatile int sc1Idx = 0;
    // E1 incremental: true cuando un colapso de almacén detuvo el escenario 1 (no se procesan más ventanas).
    private volatile boolean sc1Colapso = false;
    // E1 incremental: último nivel de alerta logueado (throttle de consola).
    private volatile String sc1NivelAlertaPrevio = AlertaColapso.VERDE;
    private volatile int sc1Envios = 0;
    private volatile int sc1Enrutadas = 0;
    private volatile int sc1SinRuta = 0;
    private volatile int sc1CumpleSLA = 0;
    private volatile int sc1Tardadas = 0;
    private volatile long sc1Maletas = 0L;
    private volatile TaStats sc1TaStats = new TaStats();
    private volatile BacklogManager sc1Backlog = null;
    private volatile List<SimulacionResponse.BloqueSimulacion> sc1Bloques = new ArrayList<>();
    private volatile Map<String, LuggageBatch> sc1AuditAcc = new LinkedHashMap<>();
    private volatile String sc1Motor = MOTOR_ALNS;
    private volatile long sc1Seed = 0L;
    private final Map<String, int[]> sc1OdStats = new HashMap<>();
    // Cancelaciones de vuelo en vivo del modo incremental de E1 (no usa JobState). El endpoint
    // /escenario1/cancelar-vuelo encola aquí y procesarSiguienteVentana las drena.
    private final java.util.Queue<CancelacionVueloRequest> sc1CancelacionesVuelo =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    // Cancelaciones YA aplicadas en E1 incremental (con envíos afectados); las expone
    // GET /escenario1/estado para que el front retire del mapa los vuelo-días cancelados.
    private final List<VueloCancelado> sc1VuelosCancelados =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    // ── CONSTRUCTOR UNIFICADO (VITAL PARA SPRING BOOT) ──────────────────
    public PlanificadorService(DataLoader dataLoader,
                               AlgorithmMapper mapper,
                               PlanificadorProperties props,
                               JobsRegistry jobs,
                               AeropuertoLoader aeropuertoLoader,
                               GraphBuilder graphBuilder,
                               EnvioLoader envioLoader,
                               AuditoriaService auditoria,
                               AcoBlockEngine acoEngine) {
        this.dataLoader = dataLoader;
        this.mapper = mapper;
        this.props = props;
        this.jobs = jobs;
        this.aeropuertoLoader = aeropuertoLoader;
        this.graphBuilder = graphBuilder;
        this.envioLoader = envioLoader;
        this.auditoria = auditoria;
        this.acoEngine = acoEngine;
    }


    // =========================================================
    // Lanzadores async (escenarios 2 y 3)
    // =========================================================

    /**
     * Lanza el escenario 2 con todos los parámetros de {@link EjecucionParams}.
     * Cualquier campo null se completa con el default del yaml.
     */
    public JobState iniciarEscenario2Async(EjecucionParams params) {
        if (params == null) params = new EjecucionParams();
        int k = params.getK() != null ? params.getK() : props.getScenario().getKDefault2();
        String motorRes = resolverMotor(params.getMotor());
        long seedRes = resolverSeed(params.getSeed());

        JobState job = jobs.crear("2", k);
        job.algoritmo = motorRes;
        job.seed = seedRes;
        job.fechaInicio = params.getFechaInicio();

        // Propagar el seed resuelto al params para que ejecutarALNS use el mismo.
        EjecucionParams pf = params;
        pf.setK(k);
        pf.setMotor(motorRes);
        pf.setSeed(seedRes);

        jobs.ejecutar(job, () -> {
            SimulacionResponse res = ejecutarALNS(pf, job);
            job.resultado = res;
        });
        return job;
    }

    /**
     * Análogo a {@link #iniciarEscenario2Async} pero para escenario 3.
     */
    public JobState iniciarEscenario3Async(int k, double umbralColapso, String motor, Long seed) {
        String motorRes = resolverMotor(motor);
        long seedRes = resolverSeed(seed);
        JobState job = jobs.crear("3", k);
        job.algoritmo = motorRes;
        job.seed = seedRes;
        jobs.ejecutar(job, () -> {
            SimulacionResponse res = ejecutarHastaColapso(k, umbralColapso, job, motorRes, seedRes);
            job.resultado = res;
        });
        return job;
    }

    /**
     * Lanza el escenario 1 como job asíncrono. K se fija al default del yaml
     * (día a día) y el bucle duerme {@code Sa - Ta} entre bloques cuando
     * {@code simularTiempoReal1=true}, replicando el ritmo real.
     */
    public JobState iniciarEscenario1Async(String motor, Long seed) {
        String motorRes = resolverMotor(motor);
        long seedRes = resolverSeed(seed);
        int k = props.getScenario().getKDefault1();
        JobState job = jobs.crear("1", k);
        job.algoritmo = motorRes;
        job.seed = seedRes;
        jobs.ejecutar(job, () -> {
            SimulacionResponse res = ejecutarEscenario1(job, motorRes, seedRes);
            job.resultado = res;
        });
        return job;
    }

    private static long resolverSeed(Long seed) {
        // Si el cliente no pasa seed, generamos uno aleatorio para que la corrida
        // siga siendo reproducible (el valor se reporta en JobState.seed).
        return seed != null ? seed : new java.util.Random().nextLong();
    }

    private static String resolverMotor(String motor) {
        if (motor == null) return MOTOR_ALNS;
        String m = motor.toLowerCase();
        if (!MOTOR_ALNS.equals(m) && !MOTOR_ACO.equals(m)) {
            throw new IllegalArgumentException("Motor desconocido: " + motor + " (use 'alns' o 'aco')");
        }
        return m;
    }

    /**
     * Atajo para consultar estado de un job.
     */
    public JobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Cancela un job en ejecución.
     */
    public boolean cancelarJob(String jobId) {
        return jobs.cancelar(jobId);
    }

    /** Lista los jobs activos (encolado/calentando/ejecutando). */
    public List<JobState> listarJobsActivos() {
        return jobs.listarActivos();
    }

    /** Lista todos los jobs vivos en memoria (activos y terminados). */
    public List<JobState> listarTodosLosJobs() {
        return jobs.listarTodos();
    }

    /** Posición del job en la cola del executor (1-based; 0 si ya corre o no existe). */
    public int posicionEnCola(String jobId) {
        return jobs.posicionEnCola(jobId);
    }

    // =========================================================
    // Cancelación de vuelos en vivo (orden del usuario desde el front)
    // =========================================================

    /**
     * Solicita cancelar un vuelo EN VIVO para un job async (E1 async / E2 / E3). Encola la orden;
     * el worker la aplica al inicio del próximo bloque (ver {@link #aplicarCancelacionesVuelo}).
     *
     * @return true si se encoló; false si el job no existe o ya terminó.
     */
    public boolean solicitarCancelacionVuelo(String jobId, CancelacionVueloRequest orden) {
        if (orden == null) return false;
        JobState job = jobs.get(jobId);
        if (job == null) return false;
        if (!JobsRegistry.ESTADOS_ACTIVOS.contains(job.estado)) return false;
        job.encolarCancelacionVuelo(orden);
        log.info("Cancelación de vuelo encolada (job {}): {}->{} salida {}", jobId,
                orden.getOrigen(), orden.getDestino(), orden.getFechaHoraSalida());
        return true;
    }

    /**
     * Variante para el modo incremental de E1 (paso a paso, sin JobState). La orden se aplica al
     * comienzo de la próxima {@link #procesarSiguienteVentana()}.
     *
     * @return true si se encoló; false si el escenario 1 no está inicializado.
     */
    public synchronized boolean solicitarCancelacionVueloEsc1(CancelacionVueloRequest orden) {
        if (orden == null || sc1Graph == null) return false;
        sc1CancelacionesVuelo.add(orden);
        log.info("Cancelación de vuelo encolada (E1 incremental): {}->{} salida {}",
                orden.getOrigen(), orden.getDestino(), orden.getFechaHoraSalida());
        return true;
    }

    /**
     * Drena la cola de órdenes de cancelación y las aplica sobre la corrida en curso: marca cada
     * vuelo-día como no disponible en el enrutador (capacidad 0) y devuelve al backlog los envíos ya
     * comprometidos en él, que {@code procesarBloque} liberará y re-enrutará en el bloque actual.
     *
     * @return cantidad de vuelo-días efectivamente cancelados en esta llamada (para acumular).
     */
    private int aplicarCancelacionesVuelo(java.util.Queue<CancelacionVueloRequest> cola, Graph graph,
                                          GreedyRepairOperator enrutador, BacklogManager backlog,
                                          Map<String, LuggageBatch> auditAcc,
                                          List<VueloCancelado> registro) {
        if (cola == null || cola.isEmpty() || graph == null || enrutador == null) return 0;
        int cancelados = 0;
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
            long epochMin = epochDay * FlightKeyEncoder.DAY_MIN;

            // Localizar el/los Edge que casan trayecto (origen→destino) + hora de salida.
            List<Edge> matches = new ArrayList<>();
            for (Edge e : graph.edges) {
                if (e.from != null && e.to != null
                        && origen.equalsIgnoreCase(e.from.code)
                        && destino.equalsIgnoreCase(e.to.code)
                        && e.depMinuteOfDay == depMinDia) {
                    matches.add(e);
                }
            }
            if (matches.isEmpty()) {
                log.warn("Cancelación: no se encontró vuelo {}->{} con salida {} (min-del-día {})",
                        origen, destino, dep, depMinDia);
                continue;
            }

            for (Edge e : matches) {
                if (enrutador.addCancelledFlight(FlightKeyEncoder.flightKey(e.idx, epochMin))) {
                    cancelados++;
                }
            }
            int afectados = reencolarAfectadosPorCancelacion(matches, epochDay, backlog, auditAcc);
            if (registro != null) {
                registro.add(new VueloCancelado(origen, destino, dep, afectados));
            }
            log.info("Vuelo cancelado {}->{} salida {} ({} edge-día) — {} envíos devueltos al backlog",
                    origen, destino, dep, matches.size(), afectados);
        }
        return cancelados;
    }

    /**
     * Devuelve al backlog (como replanificables) los envíos ya comprometidos cuya ruta usa alguno de
     * los {@code edgesCancelados} en el día {@code epochDay}. No libera capacidad aquí:
     * {@code procesarBloque} llama {@code releaseFromGlobal} + {@code clearRoute} al sacarlos del
     * backlog, reutilizando el mecanismo de re-enrutamiento existente. Si el envío VENCE antes de
     * ser reprocesado, la liberación corre por el hook de descarte del backlog (ver
     * {@code crearBacklogConPurga}) — sin él, la ruta rota quedaría cobrada para siempre.
     */
    private int reencolarAfectadosPorCancelacion(List<Edge> edgesCancelados, long epochDay,
                                                 BacklogManager backlog,
                                                 Map<String, LuggageBatch> auditAcc) {
        if (backlog == null || auditAcc == null) return 0;
        java.util.Set<Integer> idxCancelados = new java.util.HashSet<>();
        for (Edge e : edgesCancelados) idxCancelados.add(e.idx);

        int afectados = 0;
        for (LuggageBatch b : auditAcc.values()) {
            List<Edge> ruta = b.getAssignedRoute();
            List<Long> deps = b.getAssignedDepartures();
            if (ruta == null || ruta.isEmpty() || deps == null || deps.size() != ruta.size()) continue;
            boolean usa = false;
            for (int i = 0; i < ruta.size(); i++) {
                if (idxCancelados.contains(ruta.get(i).idx)
                        && (deps.get(i) / FlightKeyEncoder.DAY_MIN) == epochDay) {
                    usa = true;
                    break;
                }
            }
            if (usa) {
                backlog.addReplanificable(b);
                afectados++;
            }
        }
        return afectados;
    }

    /**
     * Mapa estático de aeropuertos del dataset cargado. Pensado para que el
     * front cachee las coordenadas al arrancar la sesión y pueda dibujar
     * los bloques de forma incremental sin esperar a {@code /resultado}.
     */
    public Map<String, SimulacionResponse.AeropuertoDTO> getAeropuertosInfo() {
        Map<String, SimulacionResponse.AeropuertoDTO> info = new LinkedHashMap<>();
        for (Aeropuerto a : dataLoader.getAeropuertos()) {
            SimulacionResponse.AeropuertoDTO dto = new SimulacionResponse.AeropuertoDTO();
            dto.setCodigo(a.getCodigo());
            dto.setLatitud(a.getLatitud() != null ? a.getLatitud() : 0.0);
            dto.setLongitud(a.getLongitud() != null ? a.getLongitud() : 0.0);
            dto.setCapacidadAlmacen(a.getCapacidad());
            info.put(a.getCodigo(), dto);
        }
        return info;
    }

    /**
     * Metadatos del dataset cargado (rango de fechas, días disponibles, total
     * de maletas). Útil para que el front valide {@code fechaInicio} contra
     * el rango antes de invocar {@code /escenario2/iniciar}.
     *
     * <p>Devuelve nulls en los campos de fecha si el dataset está vacío.
     */
    public Map<String, Object> getDatasetInfo() {
        LocalDateTime primera = dataLoader.getPrimeraVentana();
        LocalDateTime ultima  = dataLoader.getUltimaVentana();
        long diasDisponibles = 0L;
        if (primera != null && ultima != null) {
            diasDisponibles = java.time.Duration.between(primera, ultima).toDays();
            if (diasDisponibles < 1) diasDisponibles = 1; // mínimo 1 día si hay datos
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primeraVentana", primera != null ? primera.toString() : null);
        out.put("ultimaVentana",  ultima  != null ? ultima.toString()  : null);
        out.put("diasDisponibles", diasDisponibles);
        // totalMaletas queda por compatibilidad: historicamente equivale a filas/envios.
        out.put("totalMaletas", dataLoader.getTotalMaletas());
        out.put("totalEnvios", dataLoader.getTotalEnvios());
        out.put("totalMaletasIndividuales", dataLoader.getTotalMaletasIndividuales());
        out.put("totalAeropuertos", dataLoader.getAeropuertos().size());
        out.put("totalVuelos", dataLoader.getVuelos().size());
        return out;
    }

    /**
     * Read model liviano para dashboard operativo. No modifica el job ni fuerza
     * recalculos del motor; usa el resultado final si existe o los bloques ya
     * publicados si el job sigue corriendo.
     */
    public Map<String, Object> getDashboardJob(String jobId) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        SimulacionResponse.Metricas metricas = job.resultado != null
                ? job.resultado.getMetricas()
                : metricasDesdeBloques(job.bloquesDesde(0));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.getJobId());
        body.put("escenario", job.getEscenario());
        body.put("algoritmo", job.algoritmo);
        body.put("estado", job.estado);
        body.put("k", job.getK());
        body.put("seed", job.seed);
        if (job.fechaInicio != null) body.put("fechaInicio", job.fechaInicio.toString());
        body.put("inicio", job.inicio.toString());
        if (job.fin != null) body.put("fin", job.fin.toString());
        body.put("progreso", job.getProgreso());
        body.put("progresoWarmup", job.getProgresoWarmup());
        body.put("bloqueActual", job.bloqueActual);
        body.put("totalBloques", job.totalBloques);
        body.put("bloquesPublicados", job.bloquesPublicados());
        body.put("posicionEnCola", posicionEnCola(jobId));
        body.put("canceladoPorUsuario", job.canceladoPorUsuario);
        if (job.error != null) body.put("error", job.error);
        body.put("metricas", metricas);
        body.put("tasas", tasas(metricas));
        body.put("ultimoBloque", ultimoBloqueResumen(job));
        return body;
    }

    public Map<String, Object> getIndicadoresJob(String jobId) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("umbrales", Map.of(
                "verdeHasta", CostFunction.UMBRAL_VERDE,
                "ambarHasta", CostFunction.UMBRAL_AMBAR
        ));
        body.put("vuelos", getCargaVuelosJob(jobId).get("vuelos"));
        body.put("almacenes", getOcupacionAlmacenesJob(jobId).get("almacenes"));
        return body;
    }

    public Map<String, Object> getCargaVuelosJob(String jobId) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        List<Map<String, Object>> vuelos = new ArrayList<>();
        for (SimulacionResponse.BloqueSimulacion bloque : job.bloquesDesde(0)) {
            for (SimulacionResponse.CargaVuelo carga : cargasDelBloque(bloque)) {
                Map<String, Object> row = cargaVueloToMap(carga);
                row.put("bloqueIdx", bloque.getBloqueIdx());
                row.put("horaInicio", bloque.getHoraInicio());
                row.put("horaFin", bloque.getHoraFin());
                vuelos.add(row);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("total", vuelos.size());
        body.put("vuelos", vuelos);
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
        Map<String, VueloUsadoAccumulator> acc = new LinkedHashMap<>();

        for (SimulacionResponse.BloqueSimulacion bloque : job.bloquesDesde(desdeNormalizado)) {
            if (bloque.getAsignaciones() == null) continue;

            for (int asignacionIdx = 0; asignacionIdx < bloque.getAsignaciones().size(); asignacionIdx++) {
                SimulacionResponse.AsignacionMaleta asignacion = bloque.getAsignaciones().get(asignacionIdx);
                if (asignacion == null
                        || !asignacion.isEnrutada()
                        || asignacion.getTramos() == null
                        || asignacion.getTramos().isEmpty()) continue;

                String envioId = safe(asignacion.getBatchId());
                String envioKey = !envioId.isEmpty()
                        ? envioId
                        : "sin-id:" + bloque.getBloqueIdx() + ":" + asignacionIdx;

                for (SimulacionResponse.TramoRuta tramo : asignacion.getTramos()) {
                    if (tramo == null) continue;

                    String vueloId = safe(tramo.getVueloId());
                    // Eje UTC: flightKey y fechas del vuelo-día en el MISMO eje que TramoRuta.salidaUtc
                    // y CargaVuelo.fechaSalida. Las horas locales mezclan husos (salida local del
                    // origen vs llegada local del destino) y no sirven como eje del mapa.
                    String salida = safe(tramo.getSalidaUtc());
                    String llegada = safe(tramo.getLlegadaUtc());
                    String key = bloque.getBloqueIdx() + "|" + vueloId + "|" + salida;

                    VueloUsadoAccumulator vuelo = acc.computeIfAbsent(key, k -> {
                        VueloUsadoAccumulator nuevo = new VueloUsadoAccumulator();
                        nuevo.row.setFlightKey(vueloId + "|" + salida);
                        nuevo.row.setBloqueIdx(bloque.getBloqueIdx());
                        nuevo.row.setHoraInicio(bloque.getHoraInicio());
                        nuevo.row.setHoraFin(bloque.getHoraFin());
                        nuevo.row.setVueloId(vueloId);
                        nuevo.row.setOrigen(safe(tramo.getOrigen()));
                        nuevo.row.setDestino(safe(tramo.getDestino()));
                        nuevo.row.setFechaSalida(salida);
                        nuevo.row.setFechaLlegada(llegada);
                        return nuevo;
                    });

                    if (vuelo.envioKeys.add(envioKey)) {
                        vuelo.row.setCantidadMaletas(vuelo.row.getCantidadMaletas() + asignacion.getCantidad());
                        if (!envioId.isEmpty()) {
                            vuelo.envioIds.add(envioId);
                        }
                    }
                }
            }
        }

        List<VuelosUsadosResponse.VueloUsado> vuelos = acc.values().stream()
                .map(VueloUsadoAccumulator::toDto)
                .sorted(Comparator.comparingInt(VuelosUsadosResponse.VueloUsado::getBloqueIdx)
                        .thenComparing(VuelosUsadosResponse.VueloUsado::getFechaSalida)
                        .thenComparing(VuelosUsadosResponse.VueloUsado::getVueloId))
                .collect(Collectors.toList());

        VuelosUsadosResponse response = new VuelosUsadosResponse();
        response.setJobId(jobId);
        response.setDesde(desdeNormalizado);
        response.setBloquesPublicados(job.bloquesPublicados());
        response.setTerminado(!JobsRegistry.ESTADOS_ACTIVOS.contains(job.estado));
        response.setTotal(vuelos.size());
        response.setVuelos(vuelos);
        return response;
    }

    public Map<String, Object> getOcupacionAlmacenesJob(String jobId) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        List<Map<String, Object>> almacenes = new ArrayList<>();
        for (SimulacionResponse.BloqueSimulacion bloque : job.bloquesDesde(0)) {
            for (SimulacionResponse.OcupacionAlmacen ocupacion : ocupacionesDelBloque(bloque)) {
                Map<String, Object> row = ocupacionAlmacenToMap(ocupacion);
                row.put("bloqueIdx", bloque.getBloqueIdx());
                row.put("horaInicio", bloque.getHoraInicio());
                row.put("horaFin", bloque.getHoraFin());
                almacenes.add(row);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("total", almacenes.size());
        body.put("almacenes", almacenes);
        return body;
    }

    public Map<String, Object> getAsignacionesJob(String jobId, int desde,
                                                  String aeropuerto,
                                                  String vueloId,
                                                  boolean soloEnrutadas) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        String aeropuertoNorm = normalizarCodigo(aeropuerto);
        String vueloNorm = normalizarTexto(vueloId);
        List<Map<String, Object>> asignaciones = new ArrayList<>();

        for (SimulacionResponse.BloqueSimulacion bloque : job.bloquesDesde(desde)) {
            if (bloque.getAsignaciones() == null) continue;
            for (SimulacionResponse.AsignacionMaleta asignacion : bloque.getAsignaciones()) {
                if (soloEnrutadas && !asignacion.isEnrutada()) continue;
                if (aeropuertoNorm != null && !pasaFiltroAeropuerto(asignacion, aeropuertoNorm)) continue;
                if (vueloNorm != null && !pasaFiltroVuelo(asignacion, vueloNorm)) continue;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bloqueIdx", bloque.getBloqueIdx());
                row.put("horaInicio", bloque.getHoraInicio());
                row.put("horaFin", bloque.getHoraFin());
                row.put("asignacion", asignacion);
                asignaciones.add(row);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("desde", Math.max(0, desde));
        body.put("aeropuerto", aeropuertoNorm);
        body.put("vueloId", vueloNorm);
        body.put("soloEnrutadas", soloEnrutadas);
        body.put("total", asignaciones.size());
        body.put("asignaciones", asignaciones);
        return body;
    }

    public Map<String, Object> getDemandaResumen(LocalDateTime desde,
                                                 LocalDateTime hasta,
                                                 int top) {
        LocalDateTime primera = dataLoader.getPrimeraVentana();
        LocalDateTime ultima = dataLoader.getUltimaVentana();
        LocalDateTime inicio = desde != null ? desde : primera;
        LocalDateTime fin = hasta != null ? hasta : (ultima != null ? ultima.plusMinutes(1) : null);
        int limite = Math.max(1, Math.min(top <= 0 ? 20 : top, 200));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("desde", inicio != null ? inicio.toString() : null);
        body.put("hasta", fin != null ? fin.toString() : null);
        body.put("top", limite);

        if (inicio == null || fin == null || !inicio.isBefore(fin)) {
            body.put("totalEnvios", 0);
            body.put("totalMaletas", 0L);
            body.put("porOrigen", List.of());
            body.put("porDestino", List.of());
            body.put("porOD", List.of());
            return body;
        }

        Map<String, long[]> porOrigen = new HashMap<>();
        Map<String, long[]> porDestino = new HashMap<>();
        Map<String, long[]> porOd = new HashMap<>();
        long totalMaletas = 0L;
        int totalEnvios = 0;

        for (Maleta maleta : dataLoader.getMaletasEnRango(inicio, fin)) {
            String origen = maleta.getAeropuertoOrigen() != null ? maleta.getAeropuertoOrigen().getCodigo() : "";
            String destino = maleta.getAeropuertoDestino() != null ? maleta.getAeropuertoDestino().getCodigo() : "";
            long cantidad = maleta.getCantidad() != null ? maleta.getCantidad() : 0L;
            totalEnvios++;
            totalMaletas += cantidad;
            acumularDemanda(porOrigen, origen, cantidad);
            acumularDemanda(porDestino, destino, cantidad);
            acumularDemanda(porOd, origen + "->" + destino, cantidad);
        }

        body.put("totalEnvios", totalEnvios);
        body.put("totalMaletas", totalMaletas);
        body.put("porOrigen", demandaRows(porOrigen, limite));
        body.put("porDestino", demandaRows(porDestino, limite));
        body.put("porOD", demandaRows(porOd, limite));
        return body;
    }

    // =========================================================
    // Escenario 2: Simulación de período (batch completo)
    // =========================================================
    public SimulacionResponse ejecutarALNS(int k) {
        return ejecutarALNS(k, null, MOTOR_ALNS, resolverSeed(null), null);
    }

    public SimulacionResponse ejecutarALNS(int k, JobState job) {
        return ejecutarALNS(k, job, MOTOR_ALNS, resolverSeed(null), null);
    }

    public SimulacionResponse ejecutarALNS(int k, JobState job, String motor) {
        return ejecutarALNS(k, job, motor, resolverSeed(null), null);
    }

    public SimulacionResponse ejecutarALNS(int k, JobState job, String motor, long seed) {
        return ejecutarALNS(k, job, motor, seed, null);
    }

    public SimulacionResponse ejecutarALNS(int k, JobState job, String motor,
                                            long seed, LocalDateTime fechaInicio) {
        EjecucionParams p = new EjecucionParams();
        p.setK(k);
        p.setMotor(motor);
        p.setSeed(seed);
        p.setFechaInicio(fechaInicio);
        return ejecutarALNS(p, job);
    }

    /**
     * Método principal de simulación. Cualquier campo {@code null} en
     * {@link EjecucionParams} cae al default global del yaml.
     *
     * <p>Permite override por petición de {@code Sa}, {@code Ta} y {@code dias}
     * — la ventana temporal se calcula dinámicamente como
     * {@code ventanasTotales = (dias·24·60)/Sa} sin acoplarse a {@code max-ventanas}.
     */
    public SimulacionResponse ejecutarALNS(EjecucionParams params, JobState job) {
        if (params == null) params = new EjecucionParams();
        int k = params.getK() != null ? params.getK() : props.getScenario().getKDefault2();
        String motor = params.getMotor();
        long seed = resolverSeed(params.getSeed());
        LocalDateTime fechaInicio = params.getFechaInicio();
        Integer saOverride = params.getSaMin();
        Integer taOverride = params.getTaSegundos();
        Integer diasOverride = params.getDias();

        String motorRes = resolverMotor(motor);
        Random rngSim = new Random(seed);
        int saMin = (saOverride != null && saOverride > 0)
                ? saOverride
                : props.getScenario().getSaMinutos();
        long taFijoMs = (taOverride != null && taOverride > 0)
                ? taOverride * 1000L
                : props.getScenario().getTaSegundos() * 1000L;
        int scMin = Math.max(saMin, k * saMin);
        log.info("Escenario 2 — motor={} seed={} fechaInicio={} K={} Sa={}min Ta={}s dias={} Sc={}min async={}",
                motorRes, seed, fechaInicio, k, saMin, taFijoMs / 1000, diasOverride, scMin, job != null);
        long inicio = System.currentTimeMillis();

        List<TemporalContext> plan = construirPlanBloques(k, fechaInicio, saOverride, diasOverride);
        if (plan.isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k);
            r.setSaMinutos(saMin);
            return r;
        }

        // Warm-up (procesamiento previo): si la fechaInicio del usuario está por delante de la
        // primera ventana del dataset, simulamos primero [primera, fechaInicio) para que el motor
        // llegue a fechaInicio con backlog/ocupaciones realistas. Los bloques de warm-up NO se
        // publican al front ni cuentan en la auditoría. DESACTIVADO POR DEFECTO: solo se ejecuta
        // si la petición lo pide explícitamente (params.procesamientoPrevio); si no, la simulación
        // arranca directamente en fechaInicio sin procesar el período anterior.
        List<TemporalContext> warmupPlan = params.isProcesamientoPrevio()
                ? construirPlanWarmup(k, fechaInicio, saOverride)
                : Collections.emptyList();

        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        enrutador.configurarStorageAware(props.getStorageAware().getUmbralHubPico(),
                props.getStorageAware().getPrecioHubExponente());   // Fase P
        AlnsSolution solucionDummy = new AlnsSolution(Collections.emptyList());

        int totalBloques = plan.size();
        int intervaloReporte = Math.max(1, totalBloques / 10);

        // Cancelaciones de vuelo: ya no se sortean al azar. Se aplican en vivo cuando el usuario
        // las ordena desde el front (ver aplicarCancelacionesVuelo). Acumula el total ordenado.
        // Con job, el registro vive en el JobState para que GET /jobs/{id}/estado lo exponga en vivo.
        int totalVuelosCancelados = 0;
        List<VueloCancelado> vuelosCancelados = job != null ? job.getVuelosCancelados() : new ArrayList<>();

        List<SimulacionResponse.BloqueSimulacion> bloques = new ArrayList<>(totalBloques);
        Map<String, int[]> odStats = new HashMap<>();
        int totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
                totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        TaStats taStats = new TaStats();
        boolean simularTiempoReal = props.getScenario().isSimularTiempoReal2();
        long saMs = saMin * 60_000L;
        // G2: purga activa para acotar el backlog (los vencidos dejan de reintentarse).
        BacklogManager backlog = crearBacklogConPurga(enrutador);
        Map<String, LuggageBatch> auditAcc = new LinkedHashMap<>();

        // ── Fase warm-up ────────────────────────────────────────────────────
        // Se ejecuta el plan [primera-ventana, fechaInicio) compartiendo
        // graph/enrutador/backlog/odStats con la fase visible. Auditoría y
        // métricas del warm-up van a un acumulador descartable.
        if (!warmupPlan.isEmpty()) {
            if (job != null) {
                job.estado = "calentando";
                job.totalBloquesWarmup = warmupPlan.size();
                job.bloqueWarmup = 0;
            }
            Map<String, LuggageBatch> auditWarmup = new LinkedHashMap<>();
            int intervaloWarmup = Math.max(1, warmupPlan.size() / 10);
            long inicioWarmupMs = System.currentTimeMillis();
            log.info("Warm-up iniciado: {} bloques hasta fechaInicio={}", warmupPlan.size(), fechaInicio);
            int wIdx = 0;
            for (TemporalContext ctx : warmupPlan) {
                wIdx++;
                Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
                procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog,
                        auditWarmup, motorRes, rngBloque, taFijoMs, true);
                if (job != null) {
                    job.bloqueWarmup = wIdx;
                    if (("cancelado".equals(job.estado) || job.canceladoPorUsuario)) break;
                }
                if (wIdx % intervaloWarmup == 0 || wIdx == warmupPlan.size()) {
                    log.info("Warm-up ({}): {}% — {}/{} | backlog actual={}",
                            motorRes, (int) Math.round(wIdx * 100.0 / warmupPlan.size()),
                            wIdx, warmupPlan.size(), backlog.size());
                }
            }
            log.info("Warm-up completado en {} ms (backlog={}, pico={})",
                    System.currentTimeMillis() - inicioWarmupMs,
                    backlog.size(), backlog.picoHistorico());
            if (job != null && !("cancelado".equals(job.estado) || job.canceladoPorUsuario)) {
                job.estado = "ejecutando";
            }
        }

        // Fase T (N3) — pre-calienta la caché de esqueletos con la demanda de toda la ventana antes
        // del bucle de bloques: mueve el costo del Dijkstra FUERA del presupuesto Ta (sube throughput
        // en arranque limpio / caché fría). Ta-safe y no cambia rutas (la materialización revalida
        // capacidad por bloque). Reversible con planificador.scenario.prewarm-skeletons=false.
        if (props.getScenario().isPrewarmSkeletons() && !plan.isEmpty()) {
            long t0Prewarm = System.currentTimeMillis();
            List<Maleta> demandaVentana = dataLoader.getMaletasEnRango(
                    plan.get(0).scStart, plan.get(plan.size() - 1).scEnd);
            int clavesCalentadas = enrutador.precalentarEsqueletos(
                    mapper.mapToBatches(demandaVentana), PREWARM_ROUTE_CANDIDATES);
            log.info("Pre-warm esqueletos (N3): {} claves desde {} envíos en {} ms",
                    clavesCalentadas, demandaVentana.size(), System.currentTimeMillis() - t0Prewarm);
        }

        boolean colapsoAlmacenDetectado = false;   // E2 se detiene ante colapso de almacén.
        int bloqueColapsoAlmacen = -1;
        String nivelAlertaPrevio = AlertaColapso.VERDE;
        for (TemporalContext ctx : plan) {
            bloqueActual++;
            // Cancelaciones de vuelo ordenadas por el usuario en vivo: se aplican antes de procesar.
            totalVuelosCancelados += aplicarCancelacionesVuelo(
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, auditAcc, vuelosCancelados);
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motorRes, rngBloque, taFijoMs);
            bloques.add(rv.bloque);
            taStats.acumular(ctx.taMs);

            TotalesUnicos totales = calcularTotalesUnicos(auditAcc);
            totalEnvios = totales.envios();
            totalEnrutadas = totales.enrutadas();
            totalSinRuta = totales.sinRuta();
            totalCumpleSLA = totales.cumpleSLA();
            totalTardadas = totales.tardadas();
            totalMaletas = totales.maletas();

            // Reporte de progreso al job (si está siendo ejecutado de forma async)
            if (job != null) {
                job.bloqueActual = bloqueActual;
                job.totalBloques = totalBloques;
                job.taPromedioMs = taStats.promedio();
                // Publicación incremental: el front lo consume con
                // GET /jobs/{jobId}/bloques?desde=N para dibujar en tiempo real.
                job.publicarBloque(rv.bloque);
                job.alertaColapso = rv.alerta();
                // Parada por orden del front: el usuario llamó a /cancelar. Igual que E1/E3,
                // así E2 termina de inmediato (aunque simularTiempoReal2=false) y llega a
                // publicarAuditoria para preservar el ZIP de lo procesado.
                if ("cancelado".equals(job.estado) || job.canceladoPorUsuario) {
                    log.info("E2 cancelado por usuario en bloque {}/{}", bloqueActual, totalBloques);
                    break;
                }
            }
            nivelAlertaPrevio = avisarColapsoInminente("E2", rv.alerta(), bloqueActual, nivelAlertaPrevio);

            // Propagar tasa sinRuta al siguiente bloque para iteraciones dinámicas
            if (bloqueActual < plan.size()) {
                double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
                plan.get(bloqueActual).tasaSinRutaPrevia = tasa;
            }

            // G2: purga por vencimiento — los envios cuyo deadline (readyTime+SLA)
            // ya paso dejan de reintentarse (pasan a sinRutaDefinitivo), acotando el
            // backlog y liberando Ta para los enrutables. E1/E2 no colapsan.
            backlog.purgarVencidas(ctx.scEnd);

            logBloque(motorRes, bloqueActual, totalBloques,
                    rv.envios, rv.cumpleSLA, rv.tardadas, rv.sinRuta, ctx.taMs, backlog.size(), rv.colapsoAlmacen());

            // Colapso logístico por almacén lleno: DETIENE el escenario 2.
            if (rv.colapsoAlmacen()) {
                colapsoAlmacenDetectado = true;
                bloqueColapsoAlmacen = bloqueActual;
                log.warn("E2 COLAPSO por almacén lleno en bloque {}/{} — {}",
                        bloqueActual, totalBloques, rv.detalleColapso());
                break;
            }

            // Fase O (hubs dinámicos): la reclasificación de hubs por utilización real corre dentro
            // de enrutador.commitBlock() cada N bloques (cubre E1/E2/E3 uniformemente). Sin lista
            // hardcodeada → robusto ante cambios de dataset.

            // K3: instrumentación de saturación (flight-day vs airport-day) para observar el
            // onset del primer fallo y dirigir la Fase L (¿satura vuelo o almacén de hub?).
            if (bloqueActual % 50 == 0 || bloqueActual == totalBloques) {
                log.info("--- Saturación tras bloque {}/{} ---", bloqueActual, totalBloques);
                enrutador.logEstadisticasCapacidad();
            }

            if (log.isDebugEnabled() && (bloqueActual % intervaloReporte == 0 || bloqueActual == totalBloques)) {
                log.debug("Progreso E2 ({}): {}% — {}/{} | envíos:{} maletas:{} | ok:{} tarde:{} sinRuta:{} | Ta={}ms",
                        motorRes,
                        (int) Math.round(bloqueActual * 100.0 / totalBloques),
                        bloqueActual, totalBloques,
                        totalEnvios, totalMaletas,
                        totalCumpleSLA, totalTardadas, totalSinRuta, ctx.taMs);
            }

            // Sleep para respetar el modelo Ta/Sa (eje real entre ejecuciones del algoritmo).
            // Solo activo en escenario 2 cuando simularTiempoReal2=true.
            if (simularTiempoReal && bloqueActual < totalBloques) {
                long dormirMs = saMs - ctx.taMs;
                if (dormirMs > 0) {
                    try {
                        Thread.sleep(dormirMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("E2 interrumpido en bloque {}/{}", bloqueActual, totalBloques);
                        break;
                    }
                } else {
                    log.warn("Ta={}ms > Sa={}ms en bloque {} — calibrar K hacia abajo", ctx.taMs, saMs, bloqueActual);
                }
            }
        }

        bloquesCacheados = bloques;
        long tiempoMs = System.currentTimeMillis() - inicio;
        log.info("E2 completado en {} ms — {} bloques | {} envíos | {} maletas | ok:{} tarde:{} sinRuta:{} | Ta(min/avg/max)={}/{}/{} ms (Sa={} ms) | backlog: pico={} actual={} definitivo={}",
                tiempoMs, bloques.size(), totalEnvios, totalMaletas,
                totalCumpleSLA, totalTardadas, totalSinRuta,
                taStats.min(), taStats.promedio(), taStats.max(), saMs,
                backlog.picoHistorico(), backlog.size(), backlog.sinRutaDefinitivo());
        if (colapsoAlmacenDetectado) {
            log.warn("E2 detenido por COLAPSO de almacén en bloque {}", bloqueColapsoAlmacen);
        }
        logDiagnosticos(odStats, graph, enrutador);

        SimulacionResponse res = construirRespuestaFront(0, tiempoMs,
                dataLoader.getVuelos(), bloques.size(), plan.get(0).scStart.toLocalDate());
        llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, totalVuelosCancelados,
                colapsoAlmacenDetectado, bloqueColapsoAlmacen);
        llenarMetricasTa(res.getMetricas(), taStats, saMs);
        llenarMetricasBacklog(res.getMetricas(), backlog);
        res.setK(k);
        res.setSaMinutos(saMin);

        if (job != null) job.resultado = res;
        publicarAuditoria(job, auditAcc, vuelosCancelados);
        return res;
    }

    public SimulacionResponse.BloqueSimulacion getBloque(int index) {
        if (bloquesCacheados == null || index < 0 || index >= bloquesCacheados.size()) return null;
        return bloquesCacheados.get(index);
    }

    // =========================================================
    // Escenario 1: Día a día (ventana por ventana, con estado)
    // =========================================================

    /**
     * Inicializa el estado del escenario 1. Debe llamarse antes de procesarSiguienteVentana().
     */
    public synchronized Map<String, Object> inicializarEscenario1() {
        return inicializarEscenario1(MOTOR_ALNS, null);
    }

    public synchronized Map<String, Object> inicializarEscenario1(String motor, Long seed) {
        String motorRes = resolverMotor(motor);
        long seedRes = resolverSeed(seed);
        int k = props.getScenario().getKDefault1(); // K=1 día a día
        log.info("Escenario 1 — inicializando motor={} seed={} (K={}) ...",
                motorRes, seedRes, k);

        sc1Graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        sc1Enrutador = new GreedyRepairOperator(sc1Graph);
        sc1Enrutador.configurarStorageAware(props.getStorageAware().getUmbralHubPico(),
                props.getStorageAware().getPrecioHubExponente());   // Fase P
        sc1Dummy = new AlnsSolution(Collections.emptyList());
        sc1Idx = 0;
        sc1Colapso = false;
        sc1NivelAlertaPrevio = AlertaColapso.VERDE;
        sc1Envios = sc1Enrutadas = sc1SinRuta = sc1CumpleSLA = sc1Tardadas = 0;
        sc1Maletas = 0L;
        sc1TaStats = new TaStats();
        sc1Backlog = crearBacklogConPurga(sc1Enrutador); // G2: purga activa (acota backlog)
        sc1Bloques = new ArrayList<>();
        sc1AuditAcc = new LinkedHashMap<>();
        sc1VuelosCancelados.clear();
        sc1Motor = motorRes;
        sc1Seed = seedRes;
        sc1OdStats.clear();

        sc1Plan = construirPlanBloques(k);
        log.info("E1 listo: {} bloques", sc1Plan.size());

        return Map.of(
                "estado", "inicializado",
                "totalVentanas", sc1Plan.size(),
                "ventanaActual", 0,
                "motor", motorRes,
                "seed", seedRes
        );
    }

    public ResumenPlanificacionGlobal procesarTodosLosOrigenes() {
        return procesarTodosLosOrigenesConLimite(Integer.MAX_VALUE, DEFAULT_TICK_MINUTES);
    }

    public ResumenPlanificacionGlobal procesarTodosLosOrigenesConLimite(int limitePorOrigen) {
        return procesarTodosLosOrigenesConLimite(limitePorOrigen, DEFAULT_TICK_MINUTES);
    }

    public ResumenPlanificacionGlobal procesarTodosLosOrigenesConLimite(int limitePorOrigen, int tickMinutosSimulacion) {
        TaStats.PlanRequest request = TaStats.PlanRequest.todos(limitePorOrigen, tickMinutosSimulacion);
        return procesarBase(request);
    }

    public List<PlanificacionResultado> procesarTodosLosEnvios(String origen) {
        TaStats.PlanRequest request = TaStats.PlanRequest.unOrigen(origen, Integer.MAX_VALUE, DEFAULT_TICK_MINUTES);
        return procesarBaseConResultados(request).resultados;
    }

    public List<PlanificacionResultado> procesarConLimite(String origen, int limite) {
        TaStats.PlanRequest request = TaStats.PlanRequest.unOrigen(origen, limite, DEFAULT_TICK_MINUTES);
        return procesarBaseConResultados(request).resultados;
    }

    public String ejecutarACOporEnvio(String origen, int limite) {
        TaStats.PlanRequest request = TaStats.PlanRequest.unOrigen(origen, limite, DEFAULT_TICK_MINUTES);
        ResumenPlanificacionGlobal resumen = procesarBase(request);
        return "ACO ejecutado para " + resumen.totalEnviosProcesados + " envíos desde " + origen;
    }

    public String exportarAuditoriaCsv(int limitePorOrigen, int tickMinutosSimulacion, int sampleSize, String outputPath) {
        TaStats.PlanRequest request = TaStats.PlanRequest.todos(limitePorOrigen, tickMinutosSimulacion);
        TaStats.BaseRunResult run = procesarBaseConResultados(request);

        List<TaStats.AuditRecord> registros = sampleAudit(run.auditoria, sampleSize);
        String csv = construirCsvAuditoria(registros);
        Path out = Path.of(outputPath);

        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, csv);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo escribir auditoría CSV en " + outputPath, e);
        }

        return out.toAbsolutePath().toString();
    }

    private String construirCsvAuditoria(List<TaStats.AuditRecord> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("idEnvio,origen,destino,registroHHMM,deadlineMin,exitoso,motivoFalla,ruta,numTramos,numEscalas,")
                .append("tiempoVueloMin,tiempoEsperaMin,tiempoTotalMin,llegadaMin,slackSlaMin,costoTotal,")
                .append("cumpleSLA,sinCiclos,sinDirecto,escalaMinOK,capacidadVuelosOK,almacenDestinoOK,scoreCalidad\n");
        for (TaStats.AuditRecord r : rows) {
            sb.append(csv(r.idEnvio)).append(',')
                    .append(csv(r.origen)).append(',')
                    .append(csv(r.destino)).append(',')
                    .append(csv(r.registroHHMM)).append(',')
                    .append(r.deadlineMin).append(',')
                    .append(r.exitoso).append(',')
                    .append(csv(r.motivoFalla)).append(',')
                    .append(csv(r.ruta)).append(',')
                    .append(r.numTramos).append(',')
                    .append(r.numEscalas).append(',')
                    .append(r.tiempoVueloMin).append(',')
                    .append(r.tiempoEsperaMin).append(',')
                    .append(r.tiempoTotalMin).append(',')
                    .append(r.llegadaMin).append(',')
                    .append(r.slackSlaMin).append(',')
                    .append(r.costoTotal).append(',')
                    .append(r.cumpleSla).append(',')
                    .append(r.sinCiclos).append(',')
                    .append(r.sinDirecto).append(',')
                    .append(r.escalaMinOk).append(',')
                    .append(r.capacidadVuelosOk).append(',')
                    .append(r.almacenDestinoOk).append(',')
                    .append(r.scoreCalidad)
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * Limpia o escapa los textos para que no rompan el formato del archivo CSV.
     */
    private String csv(String texto) {
        if (texto == null) {
            return "";
        }
        // Si el texto tiene comillas, saltos de línea o comas, lo escapamos correctamente
        if (texto.contains(",") || texto.contains("\"") || texto.contains("\n")) {
            return "\"" + texto.replace("\"", "\"\"") + "\"";
        }
        return texto;
    }

    public List<PlanificacionResultado> procesarTodosLosOrigenesConResultados(int limitePorOrigen, int tickMinutosSimulacion) {
        TaStats.PlanRequest request = TaStats.PlanRequest.todos(limitePorOrigen, tickMinutosSimulacion);
        return procesarBaseConResultados(request).resultados;
    }

    private ResumenPlanificacionGlobal procesarBase(TaStats.PlanRequest request) {
        return procesarBaseConResultados(request).resumen;
    }

    private TaStats.BaseRunResult procesarBaseConResultados(TaStats.PlanRequest request) {
        long startTime = System.currentTimeMillis();

        int tick = Math.max(1, request.tickMinutosSimulacion);
        int limite = (request.limitePorOrigen <= 0) ? Integer.MAX_VALUE : request.limitePorOrigen;

        List<Aeropuerto> aeropuertos = dataLoader.getAeropuertos();
        List<Vuelo> vuelos = dataLoader.getVuelos();

        Map<String, Integer> offsetPorCodigo = new HashMap<>();
        for (Aeropuerto a : aeropuertos) {
            offsetPorCodigo.put(a.getCodigo(), a.getOffsetHorario() != null ? a.getOffsetHorario() : 0);
        }


        ResumenPlanificacionGlobal resumen = new ResumenPlanificacionGlobal();
        resumen.estadisticasPorOrigen = new HashMap<>();
        List<PlanificacionResultado> resultados = new ArrayList<>();
        List<TaStats.AuditRecord> auditoria = new ArrayList<>();

        ConfigACO config = new ConfigACO();
        config.antCount = 10;
        config.iterations = 50;

        for (String origen : request.origenes) {
            // CORRECCIÓN 4: Crear la variable offsetOrigen sacándola del mapa
            int offsetOrigen = offsetPorCodigo.getOrDefault(origen, 0);

            Graph graph = mapper.mapToGraph(aeropuertos, vuelos);
            
            // FASE 2: Obtenemos los envíos directamente desde PostgreSQL ya filtrados y ordenados
            List<EnvioDTO> envios = envioLoader.cargarEnviosOptimizados(origen, limite);
            
            if (envios.isEmpty()) {
                continue;
            }

            ResumenPlanificacionGlobal.EstadisticaOrigen stats = new ResumenPlanificacionGlobal.EstadisticaOrigen();
            stats.origen = origen;

            PriorityQueue<TaStats.ScheduledEvent> eventos = new PriorityQueue<>(Comparator.comparingInt(ev -> ev.minute));
            List<EnvioDTO> pendientes = new ArrayList<>();

            int idxSiguiente = 0;
            int tiempoActual = envios.get(0).horaRegistro * 60 + envios.get(0).minutoRegistro;

            while (idxSiguiente < envios.size() || !pendientes.isEmpty() || !eventos.isEmpty()) {
                while (!eventos.isEmpty() && eventos.peek().minute <= tiempoActual) {
                    eventos.poll().action.run();
                }

                while (idxSiguiente < envios.size()) {
                    EnvioDTO proximo = envios.get(idxSiguiente);
                    int registroMin = proximo.horaRegistro * 60 + proximo.minutoRegistro;
                    if (registroMin > tiempoActual) {
                        break;
                    }
                    pendientes.add(proximo);
                    idxSiguiente++;
                }

                Iterator<EnvioDTO> it = pendientes.iterator();
                while (it.hasNext()) {
                    EnvioDTO envio = it.next();

                    if (request.destinosPermitidos != null
                            && !request.destinosPermitidos.contains(envio.destinoICAO)) {
                        continue;
                    }

                    TaStats.AttemptResult intento = intentarPlanificarEnvio(
                            origen, offsetOrigen, envio, graph, config, eventos, tiempoActual, tick
                    );

                    if (intento == null) {
                        continue;
                    }

                    PlanificacionResultado resultado = intento.resultado;

                    resultados.add(resultado);
                    auditoria.add(intento.auditRecord);
                    stats.totalEnvios++;
                    resumen.totalEnviosProcesados++;
                    resumen.totalMaletas += envio.cantidadMaletas;

                    if (resultado.exitoso) {
                        stats.exitosos++;
                        stats.rutasConEscala++;
                        resumen.totalEnviosExitosos++;
                        resumen.costoPromedioExitosos += resultado.costoTotal;
                    } else {
                        stats.fallidos++;
                    }

                    it.remove();
                }

                tiempoActual += tick;
            }

            resumen.estadisticasPorOrigen.put(origen, stats);
        }

        resumen.totalEnviosFallidos = resumen.totalEnviosProcesados - resumen.totalEnviosExitosos;
        resumen.tiempoEjecucionMs = System.currentTimeMillis() - startTime;
        if (resumen.totalEnviosExitosos > 0) {
            resumen.costoPromedioExitosos = resumen.costoPromedioExitosos / resumen.totalEnviosExitosos;
        }

        return new TaStats.BaseRunResult(resumen, resultados, auditoria);
    }

    /**
     * <b>VÍA DE PRUEBAS / DIAGNÓSTICO — NO ES PRODUCCIÓN.</b>
     *
     * <p>Ejecuta una corrida ACO directa ({@code new AlgorithmACO(...)}, el ACO
     * clásico de diagnóstico). En este modo el algoritmo usa los contadores
     * globales mutables {@code Edge.usedCapacity} y {@code Node.storeLoad}, que
     * NO respetan el modelo flight-day/airport-day que sí aplica la vía de
     * producción ({@link AcoBlockEngine}).
     *
     * <p>Solo se invoca desde flujos de simulación interna
     * ({@code procesarBaseConResultados}, {@code ejecutarHastaColapso}) y tests.
     * Sus métricas <b>no son comparables</b> con las que produce el endpoint
     * principal de planificación.
     *
     * <p>El front <b>no llega aquí</b> en el flujo normal de planificación con
     * {@code motor=aco}; ese flujo entra por {@link AcoBlockEngine#procesar}.
     */
    private TaStats.AttemptResult intentarPlanificarEnvio(
            String origen,
            int offsetOrigen,
            EnvioDTO e,
            Graph graph,
            ConfigACO config,
            PriorityQueue<TaStats.ScheduledEvent> eventos,
            int tiempoActual,
            int tick
    ) {
        CostFunction.EnvioContext ctx = new CostFunction.EnvioContext(
                origen, e.destinoICAO, e.cantidadMaletas, e.horaRegistro, e.minutoRegistro, offsetOrigen
        );

        if (tiempoActual > ctx.deadlineMinutos) {
            PlanificacionResultado r = PlanificacionResultado.fallido(e.id, origen, e.destinoICAO, "Deadline excedido");
            return new TaStats.AttemptResult(r, TaStats.AuditRecord.fallido(origen, e, ctx.deadlineMinutos, "Deadline excedido", tiempoActual));
        }

        try {
            AlgorithmACO aco = new AlgorithmACO(graph, config, ctx);
            aco.run(origen, e.destinoICAO);
            Ant mejor = aco.getMejorAnt();

            if (mejor != null && !mejor.path.isEmpty() && !mejor.edgesPath.isEmpty()) {
                if (!e.destinoICAO.equals(mejor.path.get(mejor.path.size() - 1).code)) {
                    if (tiempoActual + tick > ctx.deadlineMinutos) {
                        PlanificacionResultado r = PlanificacionResultado.fallido(
                                e.id, origen, e.destinoICAO, "Ruta incompleta: no llega al destino"
                        );
                        return new TaStats.AttemptResult(r, TaStats.AuditRecord.fallido(
                                origen, e, ctx.deadlineMinutos,
                                "Ruta incompleta: no llega al destino", tiempoActual
                        ));
                    }
                    return null;
                }

                Node destinoFinal = graph.nodes.get(e.destinoICAO);
                if (destinoFinal == null || !destinoFinal.hasStorageCapacity(e.cantidadMaletas)) {
                    PlanificacionResultado r = PlanificacionResultado.fallido(
                            e.id, origen, e.destinoICAO, "Sin capacidad de almacenamiento en destino");
                    return new TaStats.AttemptResult(r, TaStats.AuditRecord.fallido(origen, e, ctx.deadlineMinutos,
                            "Sin capacidad de almacenamiento en destino", tiempoActual));
                }

                boolean escalaMinOk = cumpleEscalaMinima(mejor.edgesPath);
                int tiempoVueloMin = calcularTiempoVueloMin(mejor.edgesPath);
                int tiempoEsperaMin = calcularTiempoEsperaMin(mejor.edgesPath);
                int tiempoTotalMin = tiempoVueloMin + tiempoEsperaMin;
                int llegadaMin = ctx.minutosRegistro + tiempoTotalMin;
                int slack = ctx.deadlineMinutos - llegadaMin;
                boolean sinDirecto = mejor.edgesPath.size() > 1;
                boolean sinCiclos = sinCiclos(mejor.path);
                boolean capacidadVuelosOk = mejor.edgesPath.stream().allMatch(ed -> ed.hasCapacity(e.cantidadMaletas));
                boolean cumpleSLA = llegadaMin <= ctx.deadlineMinutos;
                int score = calcularScore(sinDirecto, sinCiclos, escalaMinOk, capacidadVuelosOk, true, cumpleSLA,
                        mejor.edgesPath.size() - 1, tiempoEsperaMin, slack);

                destinoFinal.storeLoad(e.cantidadMaletas);
                int minutosRuta = estimarMinutosRuta(mejor.edgesPath);
                int liberarDestinoEn = tiempoActual + minutosRuta + CostFunction.TIEMPO_DESTINO_FINAL;
                eventos.add(new TaStats.ScheduledEvent(liberarDestinoEn,
                        () -> destinoFinal.releaseLoad(e.cantidadMaletas)));

                int minutosAcumulados = 0;
                for (Edge edge : mejor.edgesPath) {
                    edge.useCapacity(e.cantidadMaletas);

                    minutosAcumulados += (int) Math.max(1, Math.round(
                            CostFunction.calcularDuracionMinutos(edge.departureTime.toString(), edge.arrivalTime.toString())
                    ));
                    int liberarEn = tiempoActual + minutosAcumulados;
                    eventos.add(new TaStats.ScheduledEvent(liberarEn,
                            () -> edge.usedCapacity = Math.max(0, edge.usedCapacity - e.cantidadMaletas)));
                    minutosAcumulados += CostFunction.TIEMPO_MIN_ESCALA;
                }

                List<String> ruta = mejor.path.stream().map(n -> n.code).toList();
                PlanificacionResultado r = new PlanificacionResultado(
                        e.id, origen, e.destinoICAO, e.cantidadMaletas, ruta, mejor.totalCost, true
                );
                TaStats.AuditRecord audit = TaStats.AuditRecord.exitoso(origen, e, ctx.deadlineMinutos, ruta, mejor.totalCost,
                        mejor.edgesPath.size(), tiempoVueloMin, tiempoEsperaMin, tiempoTotalMin, llegadaMin, slack,
                        cumpleSLA, sinCiclos, sinDirecto, escalaMinOk, capacidadVuelosOk, true, score);
                return new TaStats.AttemptResult(r, audit);
            }

            if (tiempoActual + tick > ctx.deadlineMinutos) {
                PlanificacionResultado r = PlanificacionResultado.fallido(e.id, origen, e.destinoICAO, "No se encontró ruta válida");
                return new TaStats.AttemptResult(r, TaStats.AuditRecord.fallido(origen, e, ctx.deadlineMinutos,
                        "No se encontró ruta válida", tiempoActual));
            }

            return null;
        } catch (Exception ex) {
            PlanificacionResultado r = PlanificacionResultado.fallido(e.id, origen, e.destinoICAO, ex.getMessage());
            return new TaStats.AttemptResult(r, TaStats.AuditRecord.fallido(origen, e, ctx.deadlineMinutos, ex.getMessage(), tiempoActual));
        }
    }


    /**
     * Procesa la siguiente ventana del escenario 1 y devuelve su bloque.
     * Devuelve null cuando todas las ventanas han sido procesadas.
     * Lanza IllegalStateException si no se ha llamado a inicializarEscenario1() antes.
     */
    public synchronized SimulacionResponse.BloqueSimulacion procesarSiguienteVentana() {
        if (sc1Graph == null)
            throw new IllegalStateException("Escenario 1 no inicializado — llame a /escenario1/inicializar primero");
        if (sc1Plan == null || sc1Idx >= sc1Plan.size()) {
            log.info("E1 completo: todos los bloques procesados");
            return null;
        }
        if (sc1Colapso) {
            log.info("E1 detenido por colapso de almacén — no se procesan más ventanas");
            return null;
        }

        TemporalContext ctx = sc1Plan.get(sc1Idx);
        sc1Idx++;

        // Cancelaciones de vuelo ordenadas por el usuario en vivo (E1 incremental). El modo
        // incremental no genera ZIP de auditoría, por eso no se registra la lista (null).
        aplicarCancelacionesVuelo(sc1CancelacionesVuelo, sc1Graph, sc1Enrutador, sc1Backlog, sc1AuditAcc,
                sc1VuelosCancelados);

        Random rngBloque = rngParaBloque(sc1Seed, sc1Motor, ctx.bloqueIdx);
        ResultadoVentana rv = procesarBloque(ctx, sc1Graph, sc1Enrutador, sc1Dummy, sc1OdStats, sc1Backlog, sc1AuditAcc, sc1Motor, rngBloque);
        sc1Bloques.add(rv.bloque);
        sc1TaStats.acumular(ctx.taMs);

        sc1Envios += rv.envios;
        sc1Enrutadas += rv.enrutadas;
        sc1SinRuta += rv.sinRuta;
        sc1CumpleSLA += rv.cumpleSLA;
        sc1Tardadas += rv.tardadas;
        sc1Maletas += rv.maletas;

        // Propagar tasa sinRuta al siguiente bloque (para iteraciones dinámicas).
        if (sc1Idx < sc1Plan.size()) {
            double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
            sc1Plan.get(sc1Idx).tasaSinRutaPrevia = tasa;
        }

        // G2: purga por vencimiento (acota el backlog del escenario 1 por pasos).
        if (sc1Backlog != null) sc1Backlog.purgarVencidas(ctx.scEnd);

        logBloque(sc1Motor, sc1Idx, sc1Plan.size(),
                rv.envios, rv.cumpleSLA, rv.tardadas, rv.sinRuta, ctx.taMs,
                sc1Backlog != null ? sc1Backlog.size() : 0, rv.colapsoAlmacen());

        // Colapso logístico por almacén lleno: DETIENE el escenario 1 (las próximas llamadas a
        // procesarSiguienteVentana devolverán null). Este bloque (ya procesado) se devuelve igual.
        if (rv.colapsoAlmacen()) {
            sc1Colapso = true;
            log.warn("E1 COLAPSO por almacén lleno en bloque {}/{} — {}",
                    sc1Idx, sc1Plan.size(), rv.detalleColapso());
        }
        sc1NivelAlertaPrevio = avisarColapsoInminente("E1", rv.alerta(), sc1Idx, sc1NivelAlertaPrevio);

        // Al terminar todos los bloques, emitir diagnóstico
        if (sc1Idx == sc1Plan.size()) {
            log.info("E1 finalizado — {} bloques | {} envíos | {} maletas | ok:{} tarde:{} sinRuta:{} | Ta(min/avg/max)={}/{}/{} ms | backlog: pico={} actual={} definitivo={}",
                    sc1Plan.size(), sc1Envios, sc1Maletas,
                    sc1CumpleSLA, sc1Tardadas, sc1SinRuta,
                    sc1TaStats.min(), sc1TaStats.promedio(), sc1TaStats.max(),
                    sc1Backlog.picoHistorico(), sc1Backlog.size(), sc1Backlog.sinRutaDefinitivo());
            logDiagnosticos(sc1OdStats, sc1Graph, sc1Enrutador);
        }

        return rv.bloque;
    }

    /**
     * Devuelve el estado actual del escenario 1 sin avanzar la ventana.
     */
    public Map<String, Object> getEstadoEscenario1() {
        return Map.of(
                "inicializado", sc1Graph != null,
                "ventanaActual", sc1Idx,
                "totalVentanas", sc1Plan != null ? sc1Plan.size() : 0,
                "totalEnvios", sc1Envios,
                "totalEnrutadas", sc1Enrutadas,
                "totalSinRuta", sc1SinRuta,
                "totalCumpleSLA", sc1CumpleSLA,
                "totalTardadas", sc1Tardadas,
                "totalMaletas", sc1Maletas,
                "vuelosCancelados", List.copyOf(sc1VuelosCancelados)
        );
    }

    /**
     * Devuelve un bloque ya procesado por índice (escenario 1).
     */
    public SimulacionResponse.BloqueSimulacion getBloqueEsc1(int index) {
        if (sc1Bloques == null || index < 0 || index >= sc1Bloques.size()) return null;
        return sc1Bloques.get(index);
    }

    /**
     * Ejecuta el escenario 1 como un job continuo: K=1 día-a-día, sleep
     * {@code Sa - Ta} entre bloques cuando {@code simularTiempoReal1=true}.
     * Publica bloques incrementalmente vía {@code JobState.publicarBloque} y
     * arma un {@link SimulacionResponse} agregado al final.
     */
    public SimulacionResponse ejecutarEscenario1(JobState job, String motor, long seed) {
        String motorRes = resolverMotor(motor);
        int k = props.getScenario().getKDefault1();
        int saMin = props.getScenario().getSaMinutos();
        long taFijoMs = props.getScenario().getTaSegundos() * 1000L;
        int scMin = Math.max(saMin, k * saMin);
        log.info("Escenario 1 — motor={} seed={} (K={}, Sa={}min, Sc={}min, async={}) ...",
                motorRes, seed, k, saMin, scMin, job != null);
        long inicio = System.currentTimeMillis();

        List<TemporalContext> plan = construirPlanBloques(k);
        if (plan.isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k);
            r.setSaMinutos(saMin);
            return r;
        }

        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        enrutador.configurarStorageAware(props.getStorageAware().getUmbralHubPico(),
                props.getStorageAware().getPrecioHubExponente());   // Fase P
        AlnsSolution solucionDummy = new AlnsSolution(Collections.emptyList());

        // Cancelaciones de vuelo: solo por orden del usuario en vivo (ver aplicarCancelacionesVuelo).
        // Con job, el registro vive en el JobState para que GET /jobs/{id}/estado lo exponga en vivo.
        int totalVuelosCancelados = 0;
        List<VueloCancelado> vuelosCancelados = job != null ? job.getVuelosCancelados() : new ArrayList<>();

        List<SimulacionResponse.BloqueSimulacion> bloques = new ArrayList<>(plan.size());
        Map<String, int[]> odStats = new HashMap<>();
        int totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
                totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        TaStats taStats = new TaStats();
        boolean simularTiempoReal = props.getScenario().isSimularTiempoReal1();
        long saMs = saMin * 60_000L;
        int totalBloques = plan.size();
        // G2: purga activa para acotar el backlog (los vencidos dejan de reintentarse).
        BacklogManager backlog = crearBacklogConPurga(enrutador);
        Map<String, LuggageBatch> auditAcc = new LinkedHashMap<>();
        int intervaloReporte = Math.max(1, totalBloques / 10);
        boolean colapsoAlmacenDetectado = false;   // E1 se detiene ante colapso de almacén.
        int bloqueColapsoAlmacen = -1;
        String nivelAlertaPrevio = AlertaColapso.VERDE;

        for (TemporalContext ctx : plan) {
            bloqueActual++;
            // Cancelaciones de vuelo ordenadas por el usuario en vivo: se aplican antes de procesar.
            totalVuelosCancelados += aplicarCancelacionesVuelo(
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, auditAcc, vuelosCancelados);
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motorRes, rngBloque, taFijoMs);

            rv.bloque.setTiempoProcesamientoMs(ctx.taMs);

            bloques.add(rv.bloque);
            taStats.acumular(ctx.taMs);

            TotalesUnicos totales = calcularTotalesUnicos(auditAcc);
            totalEnvios = totales.envios();
            totalEnrutadas = totales.enrutadas();
            totalSinRuta = totales.sinRuta();
            totalCumpleSLA = totales.cumpleSLA();
            totalTardadas = totales.tardadas();
            totalMaletas = totales.maletas();

            if (job != null) {
                job.bloqueActual = bloqueActual;
                job.totalBloques = totalBloques;
                job.taPromedioMs = taStats.promedio();
                job.publicarBloque(rv.bloque);
                job.alertaColapso = rv.alerta();
                if ("cancelado".equals(job.estado) || job.canceladoPorUsuario) {
                    log.info("E1 cancelado por usuario en bloque {}/{}", bloqueActual, totalBloques);
                    break;
                }
            }
            nivelAlertaPrevio = avisarColapsoInminente("E1", rv.alerta(), bloqueActual, nivelAlertaPrevio);

            if (bloqueActual < plan.size()) {
                double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
                plan.get(bloqueActual).tasaSinRutaPrevia = tasa;
            }

            // G2: purga por vencimiento — los envios cuyo deadline (readyTime+SLA)
            // ya paso dejan de reintentarse (pasan a sinRutaDefinitivo), acotando el
            // backlog y liberando Ta para los enrutables. E1/E2 no colapsan.
            backlog.purgarVencidas(ctx.scEnd);

            logBloque(motorRes, bloqueActual, totalBloques,
                    rv.envios, rv.cumpleSLA, rv.tardadas, rv.sinRuta, ctx.taMs, backlog.size(), rv.colapsoAlmacen());

            // Colapso logístico por almacén lleno: DETIENE el escenario 1.
            if (rv.colapsoAlmacen()) {
                colapsoAlmacenDetectado = true;
                bloqueColapsoAlmacen = bloqueActual;
                log.warn("E1 COLAPSO por almacén lleno en bloque {}/{} — {}",
                        bloqueActual, totalBloques, rv.detalleColapso());
                break;
            }

            if (log.isDebugEnabled() && (bloqueActual % intervaloReporte == 0 || bloqueActual == totalBloques)) {
                log.debug("Progreso E1 ({}): {}% — {}/{} | envíos:{} maletas:{} | ok:{} tarde:{} sinRuta:{} | Ta={}ms",
                        motorRes,
                        (int) Math.round(bloqueActual * 100.0 / totalBloques),
                        bloqueActual, totalBloques,
                        totalEnvios, totalMaletas,
                        totalCumpleSLA, totalTardadas, totalSinRuta, ctx.taMs);
            }

            if (simularTiempoReal && bloqueActual < totalBloques) {
                long dormirMs = saMs - ctx.taMs;
                if (dormirMs > 0) {
                    try {
                        Thread.sleep(dormirMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("E1 interrumpido en bloque {}/{}", bloqueActual, totalBloques);
                        break;
                    }
                } else {
                    log.warn("Ta={}ms > Sa={}ms en bloque {} — calibrar Ta hacia abajo", ctx.taMs, saMs, bloqueActual);
                }
            }
        }

        bloquesCacheados = bloques;
        long tiempoMs = System.currentTimeMillis() - inicio;
        log.info("E1 completado en {} ms — {} bloques | {} envíos | {} maletas | ok:{} tarde:{} sinRuta:{} | Ta(min/avg/max)={}/{}/{} ms (Sa={} ms) | backlog: pico={} actual={} definitivo={}",
                tiempoMs, bloques.size(), totalEnvios, totalMaletas,
                totalCumpleSLA, totalTardadas, totalSinRuta,
                taStats.min(), taStats.promedio(), taStats.max(), saMs,
                backlog.picoHistorico(), backlog.size(), backlog.sinRutaDefinitivo());
        if (colapsoAlmacenDetectado) {
            log.warn("E1 detenido por COLAPSO de almacén en bloque {}", bloqueColapsoAlmacen);
        }
        logDiagnosticos(odStats, graph, enrutador);

        SimulacionResponse res = construirRespuestaFront(0, tiempoMs,
                dataLoader.getVuelos(), bloques.size(), plan.get(0).scStart.toLocalDate());
        llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, totalVuelosCancelados,
                colapsoAlmacenDetectado, bloqueColapsoAlmacen);
        llenarMetricasTa(res.getMetricas(), taStats, saMs);
        llenarMetricasBacklog(res.getMetricas(), backlog);
        res.setK(k);
        res.setSaMinutos(saMin);

        if (job != null) job.resultado = res;
        publicarAuditoria(job, auditAcc, vuelosCancelados);
        return res;
    }

    // =========================================================
    // Escenario 3: Simulación hasta el colapso
    // =========================================================

    /**
     * Ejecuta el algoritmo con cancelaciones avanzando la simulación. El escenario 3
     * solo se detiene por una de tres causas:
     * <ol>
     *   <li><b>Orden del front</b>: el job fue cancelado vía {@code /cancelar}.</li>
     *   <li><b>Falta de datos</b>: se agota el plan de bloques del dataset.</li>
     *   <li><b>Backlog definitivo</b>: un envío que estaba en el backlog vio vencer
     *       su SLA ({@code readyTime + slaLimitHours < scNow}) sin entrega on-time.</li>
     * </ol>
     * El parámetro {@code umbralColapso} se mantiene por compatibilidad de API pero
     * ya no influye en la parada.
     */
    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso) {
        return ejecutarHastaColapso(k, umbralColapso, null, MOTOR_ALNS, resolverSeed(null));
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso, JobState job) {
        return ejecutarHastaColapso(k, umbralColapso, job, MOTOR_ALNS, resolverSeed(null));
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso, JobState job, String motor) {
        return ejecutarHastaColapso(k, umbralColapso, job, motor, resolverSeed(null));
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso,
                                                   JobState job, String motor, long seed) {
        String motorRes = resolverMotor(motor);
        Random rngSim = new Random(seed);
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        int saMin = props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);
        log.info("Escenario 3 — colapso motor={} seed={} (K={}, Sa={}min, Sc={}min, umbral={}%, async={}) ...",
                motorRes, seed, k, saMin, scMin,
                String.format("%.1f", umbralColapso * 100),
                job != null);
        long inicio = System.currentTimeMillis();

        List<TemporalContext> plan = construirPlanBloques(k);
        if (plan.isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k);
            r.setSaMinutos(saMin);
            return r;
        }

        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        enrutador.configurarStorageAware(props.getStorageAware().getUmbralHubPico(),
                props.getStorageAware().getPrecioHubExponente());   // Fase P
        AlnsSolution solucionDummy = new AlnsSolution(Collections.emptyList());

        // Cancelaciones de vuelo: solo por orden del usuario en vivo (ver aplicarCancelacionesVuelo).
        // Con job, el registro vive en el JobState para que GET /jobs/{id}/estado lo exponga en vivo.
        List<VueloCancelado> vuelosCancelados = job != null ? job.getVuelosCancelados() : new ArrayList<>();

        List<SimulacionResponse.BloqueSimulacion> bloques = new ArrayList<>();
        Map<String, int[]> odStats = new HashMap<>();
        int totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
                totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        boolean collapsoDetectado = false;
        int bloqueColapso = -1;
        // Motivo de parada del E3: "backlog_definitivo", "almacen_lleno", "cancelado_front" o
        // "falta_datos" (por defecto, si el bucle agota el plan).
        String motivoParada = "falta_datos";
        String nivelAlertaPrevio = AlertaColapso.VERDE;
        TaStats taStats = new TaStats();
        boolean simularTiempoReal = props.getScenario().isSimularTiempoReal3();
        long saMs = saMin * 60_000L;
        int totalBloques = plan.size();
        // E3 con purga activa: los envios cuyo SLA vence (readyTime+SLA) sin entrega
        // on-time pasan a sinRutaDefinitivo y disparan el colapso (Politica 1).
        BacklogManager backlog = crearBacklogConPurga(enrutador);
        Map<String, LuggageBatch> auditAcc = new LinkedHashMap<>();

        for (TemporalContext ctx : plan) {
            bloqueActual++;
            // Cancelaciones de vuelo ordenadas por el usuario en vivo: se aplican antes de procesar.
            aplicarCancelacionesVuelo(
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, auditAcc, vuelosCancelados);
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motorRes, rngBloque);

            rv.bloque.setTiempoProcesamientoMs(ctx.taMs);

            bloques.add(rv.bloque);
            taStats.acumular(ctx.taMs);

            TotalesUnicos totales = calcularTotalesUnicos(auditAcc);
            totalEnvios = totales.envios();
            totalEnrutadas = totales.enrutadas();
            totalSinRuta = totales.sinRuta();
            totalCumpleSLA = totales.cumpleSLA();
            totalTardadas = totales.tardadas();
            totalMaletas = totales.maletas();

            // Reporte de progreso al job (si está siendo ejecutado de forma async)
            if (job != null) {
                job.bloqueActual = bloqueActual;
                job.totalBloques = totalBloques;
                job.taPromedioMs = taStats.promedio();
                // Publicación incremental para dibujo en tiempo real desde el front.
                job.publicarBloque(rv.bloque);
                job.alertaColapso = rv.alerta();
                // Parada por orden del front: el usuario llamó a /cancelar.
                if ("cancelado".equals(job.estado) || job.canceladoPorUsuario) {
                    motivoParada = "cancelado_front";
                    log.info("E3 cancelado por usuario en bloque {}/{}", bloqueActual, totalBloques);
                    break;
                }
            }
            nivelAlertaPrevio = avisarColapsoInminente("E3", rv.alerta(), bloqueActual, nivelAlertaPrevio);

            // Propagar tasa sinRuta al siguiente bloque para iteraciones dinámicas
            if (bloqueActual < plan.size()) {
                double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
                plan.get(bloqueActual).tasaSinRutaPrevia = tasa;
            }

            // Regla de dominio (backlog definitivo): el PRIMER envío del backlog que
            // VENCE su SLA —readyTime+SLA alcanzado sin entrega on-time— detiene la
            // simulación. Un sinRuta de la ventana actual NO es incumplimiento
            // mientras le quede tiempo: se reintenta vía backlog. La purga mueve los
            // vencidos a sinRutaDefinitivo; vencidos>0 es la única condición de parada
            // por backlog (ni rutas tardías ni tamaño de backlog detienen el E3).
            int vencidos = backlog.purgarVencidas(ctx.scEnd);
            boolean backlogDefinitivo = vencidos > 0;

            logBloque(motorRes, bloqueActual, totalBloques,
                    rv.envios, rv.cumpleSLA, rv.tardadas, rv.sinRuta, ctx.taMs, backlog.size(),
                    backlogDefinitivo || rv.colapsoAlmacen());

            // Colapso logístico por almacén lleno (origen/escala/destino) — disparo inmediato.
            if (rv.colapsoAlmacen()) {
                collapsoDetectado = true;
                bloqueColapso = bloqueActual;
                motivoParada = "almacen_lleno";
                log.warn("E3 ALMACÉN LLENO en bloque {}/{} — envío {}", bloqueActual, totalBloques, rv.detalleColapso());
                break;
            }

            if (backlogDefinitivo) {
                collapsoDetectado = true;
                bloqueColapso = bloqueActual;
                motivoParada = "backlog_definitivo";
                break;
            }

            // Sleep para respetar el modelo Ta/Sa. En escenario 3 normalmente desactivado
            // (queremos llegar al colapso lo antes posible), pero soportado para inspección.
            if (simularTiempoReal && bloqueActual < totalBloques) {
                long dormirMs = saMs - ctx.taMs;
                if (dormirMs > 0) {
                    try {
                        Thread.sleep(dormirMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("E3 interrumpido en bloque {}/{}", bloqueActual, totalBloques);
                        break;
                    }
                } else {
                    log.warn("Ta={}ms > Sa={}ms en bloque {} — calibrar K hacia abajo", ctx.taMs, saMs, bloqueActual);
                }
            }
        }

        bloquesCacheados = bloques;
        long tiempoMs = System.currentTimeMillis() - inicio;
        log.info("E3 {}: {} bloques | {} envíos | {} maletas | ok:{} tarde:{} sinRuta:{} | Ta(min/avg/max)={}/{}/{} ms | backlog: pico={} actual={} definitivo={} | {} ms",
                "backlog_definitivo".equals(motivoParada)
                        ? "BACKLOG DEFINITIVO en bloque " + bloqueColapso
                        : ("almacen_lleno".equals(motivoParada)
                                ? "ALMACÉN LLENO en bloque " + bloqueColapso
                                : ("cancelado_front".equals(motivoParada)
                                        ? "CANCELADO por front en bloque " + bloqueActual
                                        : "fin por falta de datos")),
                bloques.size(), totalEnvios, totalMaletas,
                totalCumpleSLA, totalTardadas, totalSinRuta,
                taStats.min(), taStats.promedio(), taStats.max(),
                backlog.picoHistorico(), backlog.size(), backlog.sinRutaDefinitivo(), tiempoMs);

        SimulacionResponse res = construirRespuestaFront(0, tiempoMs,
                dataLoader.getVuelos(), bloques.size(), plan.get(0).scStart.toLocalDate());
        llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, 0, collapsoDetectado, bloqueColapso);
        llenarMetricasTa(res.getMetricas(), taStats, saMs);
        llenarMetricasBacklog(res.getMetricas(), backlog);
        res.setK(k);
        res.setSaMinutos(saMin);

        if (job != null) job.resultado = res;
        publicarAuditoria(job, auditAcc, vuelosCancelados);
        return res;
    }

    /**
     * Genera la auditoría como un ZIP de varios CSV (hasta
     * {@link AuditoriaService#FILAS_POR_ARCHIVO} filas por archivo) y lo cuelga del
     * {@link JobState}. Un único CSV para millones de envíos no es práctico, por lo
     * que cada archivo interno se nombra {@code <jobId>-<inicio>-<fin>.csv}.
     *
     * <p>La auditoría solo se publica para ejecuciones asíncronas (con {@code job});
     * las corridas síncronas (benchmark/comparativa) no la generan.
     */
    private void publicarAuditoria(JobState job, Map<String, LuggageBatch> auditAcc,
                                   List<VueloCancelado> vuelosCancelados) {
        if (auditoria == null || job == null) return;
        boolean hayEnvios = auditAcc != null && !auditAcc.isEmpty();
        boolean hayCancelados = vuelosCancelados != null && !vuelosCancelados.isEmpty();
        // Generar el ZIP si hay envíos auditables o, al menos, vuelos cancelados que registrar.
        if (!hayEnvios && !hayCancelados) return;
        // Si el job fue cancelado vía Future.cancel(true), el thread llega aquí con el flag
        // interrupted activo. La escritura del ZIP usa canales NIO interrumpibles
        // (Files.newOutputStream) que lanzarían ClosedByInterruptException y perderían la
        // auditoría. Limpiamos el flag para poder persistir lo simulado hasta la cancelación.
        // Es seguro: publicarAuditoria es la última operación del job (E1/E2/E3).
        Thread.interrupted(); // limpia (y descarta) el estado de interrupción del thread actual
        int envios = hayEnvios ? auditAcc.size() : 0;
        log.info("Generando auditoria ZIP: {} envios, {} vuelos cancelados (job {})",
                envios, hayCancelados ? vuelosCancelados.size() : 0, job.getJobId());
        try {
            Path path = Files.createTempFile("planificador-auditoria-" + job.getJobId() + "-", ".zip");
            path.toFile().deleteOnExit();
            int filas = auditoria.escribirZip(
                    hayEnvios ? auditAcc.values() : java.util.List.of(), path,
                    AuditoriaService.FILAS_POR_ARCHIVO, job.getJobId(), vuelosCancelados);
            job.auditoriaZipPath = path;
            job.auditoriaCsvPath = null;
            job.auditoriaCsv = null;
            job.auditoriaFilas = filas;
            log.info("Auditoria ZIP generada: {} filas (job {}) en {}", filas, job.getJobId(), path);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar auditoria ZIP", e);
        }
    }

    // =========================================================
    // Núcleo: procesa un bloque (Sc = K*Sa minutos de datos)
    // Compartido por los 3 escenarios. Mide Ta y rellena el TemporalContext.
    // Si {@code backlog} no es null, incorpora pedidos pendientes de bloques
    // anteriores (sinRuta + replanificables) al lote del bloque actual.
    // =========================================================
    private ResultadoVentana procesarBloque(TemporalContext ctx,
                                            Graph graph,
                                            GreedyRepairOperator enrutador,
                                            AlnsSolution solucionDummy,
                                            Map<String, int[]> odStats,
                                            BacklogManager backlog) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, null, MOTOR_ALNS, null);
    }

    private ResultadoVentana procesarBloque(TemporalContext ctx,
                                            Graph graph,
                                            GreedyRepairOperator enrutador,
                                            AlnsSolution solucionDummy,
                                            Map<String, int[]> odStats,
                                            BacklogManager backlog,
                                            Map<String, LuggageBatch> auditAcc) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, MOTOR_ALNS, null);
    }

    private ResultadoVentana procesarBloque(TemporalContext ctx,
                                            Graph graph,
                                            GreedyRepairOperator enrutador,
                                            AlnsSolution solucionDummy,
                                            Map<String, int[]> odStats,
                                            BacklogManager backlog,
                                            Map<String, LuggageBatch> auditAcc,
                                            String motor) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motor, null, 0L);
    }

    private ResultadoVentana procesarBloque(TemporalContext ctx,
                                            Graph graph,
                                            GreedyRepairOperator enrutador,
                                            AlnsSolution solucionDummy,
                                            Map<String, int[]> odStats,
                                            BacklogManager backlog,
                                            Map<String, LuggageBatch> auditAcc,
                                            String motor,
                                            Random rngSim) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motor, rngSim, 0L);
    }

    /**
     * Núcleo de procesamiento por bloque. {@code motor} elige el algoritmo
     * (alns | aco). {@code rngSim} es la fuente de aleatoriedad reproducible.
     * {@code taFijoMsOverride} permite override por job de {@code ta-segundos}
     * del yaml; ≤0 = usar default global.
     */
    private ResultadoVentana procesarBloque(TemporalContext ctx,
                                            Graph graph,
                                            GreedyRepairOperator enrutador,
                                            AlnsSolution solucionDummy,
                                            Map<String, int[]> odStats,
                                            BacklogManager backlog,
                                            Map<String, LuggageBatch> auditAcc,
                                            String motor,
                                            Random rngSim,
                                            long taFijoMsOverride) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog,
                auditAcc, motor, rngSim, taFijoMsOverride, false);
    }

    /**
     * Variante con {@code fastForward}: si es true, omite el padding-sleep
     * final que rellena hasta Ta. Pensada para el warm-up: queremos que los
     * bloques previos a {@code fechaInicio} acumulen estado lo más rápido
     * posible. El motor sigue corriendo con su presupuesto Ta como deadline,
     * pero su tiempo real de cómputo (≪ Ta en la mayoría de bloques) marca
     * la cadencia.
     */
    private ResultadoVentana procesarBloque(TemporalContext ctx,
                                            Graph graph,
                                            GreedyRepairOperator enrutador,
                                            AlnsSolution solucionDummy,
                                            Map<String, int[]> odStats,
                                            BacklogManager backlog,
                                            Map<String, LuggageBatch> auditAcc,
                                            String motor,
                                            Random rngSim,
                                            long taFijoMsOverride,
                                            boolean fastForward) {
        ctx.marcarInicio();

        // 1. Eje de datos: consumir [scStart, scEnd) → todo lo registrado en ese rango.
        List<Maleta> maletasVentana = dataLoader.getMaletasEnRango(ctx.scStart, ctx.scEnd);
        List<LuggageBatch> bloqueBatches = mapper.mapToBatches(maletasVentana);

        // 2. Backlog: traer pendientes de bloques anteriores sin descarte definitivo.
        if (backlog != null) {
            List<LuggageBatch> pendientes = backlog.pollPendientesUrgentes(
                    props.getBacklog().getMaxReprocesoPorBloque());

            // Liberar capacidad global de los replanificables (ya commiteados).
            for (LuggageBatch b : pendientes) {
                // Fase Origen-B — deja de cobrar su espera en origen (se evaluará para despacho;
                // evita que su propia espera bloquee su ruta). No-op si ya tiene ruta.
                enrutador.removerEsperaOrigenBacklog(b);
                if (b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty()) {
                    enrutador.releaseFromGlobal(b);
                    b.clearRoute();
                }
            }
            if (!pendientes.isEmpty()) {
                bloqueBatches = new ArrayList<>(bloqueBatches.size() + pendientes.size());
                bloqueBatches.addAll(pendientes);
                bloqueBatches.addAll(mapper.mapToBatches(maletasVentana));
            }
        }

        // 3. Motor: ALNS (Greedy + Dijkstra + ALNS) o ACO (AlgorithmACO por batch).
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        Map<Long, Integer> telemetryFlight = blockFlight;
        Map<Long, Integer> telemetryAirport = blockAirport;
        List<LuggageBatch> finalBatches;

        // Presupuesto de tiempo Ta — variable del modelo (configurable, NO medida).
        // Prioridad: override por job > props.ta-segundos > legacy 0.7·Sa.
        long saMs = ctx.saMinutos * 60_000L;
        long taFijoMs = taFijoMsOverride > 0
                ? taFijoMsOverride
                : props.getScenario().getTaSegundos() * 1000L;
        long presupuestoMs = taFijoMs > 0 ? taFijoMs : (long) (saMs * 0.7);
        long inicioMotorMs = System.currentTimeMillis();
        long inicioMotorNs = System.nanoTime();
        long deadlineMotorNs = inicioMotorNs + presupuestoMs * 1_000_000L;

        if (MOTOR_ACO.equalsIgnoreCase(motor)) {
            if (acoEngine == null) {
                throw new IllegalStateException("AcoBlockEngine no inyectado — motor 'aco' no disponible");
            }
            // Ta como cota DURA: si el ACO excede el presupuesto, aborta y los
            // batches restantes quedan sinRuta (mismo comportamiento que ALNS).
            acoEngine.procesar(graph, enrutador, bloqueBatches, blockFlight, blockAirport, rngSim, presupuestoMs);
            finalBatches = bloqueBatches;
            enrutador.commitBlock(blockFlight, blockAirport);
        } else {
            List<LuggageBatch> intra = new ArrayList<>();
            List<LuggageBatch> inter = new ArrayList<>();
            for (LuggageBatch b : bloqueBatches) {
                if (b.getSlaLimitHours() <= 24) intra.add(b);
                else inter.add(b);
            }
            for (LuggageBatch b : intra) {
                if (System.nanoTime() >= deadlineMotorNs) break;
                enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport);
            }
            for (LuggageBatch b : inter) {
                if (System.nanoTime() >= deadlineMotorNs) break;
                enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport);
            }

            long restanteAlnsMs = Math.max(0L, (deadlineMotorNs - System.nanoTime()) / 1_000_000L);
            if (restanteAlnsMs > 0 && bloqueBatches.stream().anyMatch(b -> !b.isCumpleSLA())) {
                AlgorithmALNS alns = new AlgorithmALNS(
                        graph, enrutador, bloqueBatches, blockFlight, blockAirport, props);
                if (rngSim != null) alns.setRandom(rngSim);

                // Iteraciones dinámicas: si el bloque previo tuvo alta tasa de sinRuta,
                // dedicamos más cómputo (cerca del colapso) para intentar recuperar.
                double umbralCerca = 0.10;
                int iteraciones = (ctx.tasaSinRutaPrevia >= umbralCerca)
                        ? props.getAlns().getIteracionesCercaColapso()
                        : props.getAlns().getIteracionesBase();

                // Presupuesto Ta como cota dura: el ALNS aborta si lo excede.
                alns.tiempoLimiteMs = restanteAlnsMs;

                alns.run(iteraciones);
                finalBatches = alns.getBestSolution().getBatches();
                telemetryFlight = alns.getBestBlockFlight();
                telemetryAirport = alns.getBestBlockAirport();
                enrutador.commitBlock(telemetryFlight, telemetryAirport);
            } else {
                finalBatches = bloqueBatches;
                enrutador.commitBlock(blockFlight, blockAirport);
            }
        }

        // Si el motor terminó antes de Ta y Ta es fijo, completamos con sleep
        // para que cada bloque consuma exactamente Ta de cómputo (modelo del cliente).
        // Excepción: en warm-up (fastForward=true) saltamos el padding para
        // alcanzar fechaInicio en el menor tiempo de wall-clock posible.
        if (taFijoMs > 0 && !fastForward) {
            // Fase Q: re-seed de esqueletos hub-avoiding usando el tiempo OCIOSO del bloque,
            // acotado por el deadline de Ta (deadlineMotorNs) → Ta-safe, no añade wall-clock.
            // Solo agrega opciones a la caché; el bucle caliente sigue siendo materialización pura.
            if (MOTOR_ACO.equalsIgnoreCase(motor)) {
                enrutador.reSeedHubAvoiding(props.getStorageAware().getReSeedSlice(), deadlineMotorNs);
            }
            long transcurridoMs = System.currentTimeMillis() - inicioMotorMs;
            long faltanteMs = taFijoMs - transcurridoMs;
            if (faltanteMs > 0) {
                try {
                    Thread.sleep(faltanteMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else if (transcurridoMs > taFijoMs * 1.05) {
                // Excedió el presupuesto en >5% — calibrar bajando iteraciones o subiendo Ta.
                log.warn("Bloque {} excedió Ta: {}ms > {}ms (motor={})",
                        ctx.bloqueIdx, transcurridoMs, taFijoMs, motor);
            }
        }

        List<SimulacionResponse.AsignacionMaleta> asignaciones = buildAsignaciones(finalBatches);

        int enrutadas = (int) asignaciones.stream().filter(SimulacionResponse.AsignacionMaleta::isEnrutada).count();
        int cumpleSLA = (int) asignaciones.stream().filter(a -> a.isEnrutada() && a.isCumpleSLA()).count();
        int tardadas = enrutadas - cumpleSLA;
        int sinRuta = finalBatches.size() - enrutadas;
        long maletas = maletasVentana.stream()
                .mapToLong(m -> m.getCantidad() != null ? m.getCantidad() : 0L)
                .sum();

        for (SimulacionResponse.AsignacionMaleta a : asignaciones) {
            int[] s = odStats.computeIfAbsent(a.getOrigen() + "->" + a.getDestino(), key -> new int[2]);
            s[0]++;
            if (a.isEnrutada()) s[1]++;
        }

        // 3b. Colapso logístico por ALMACÉN lleno (origen/escala/destino): un envío que NO logró
        //     entrega on-time (sinRuta, o enrutado TARDÍO — el ALNS difiere a un día posterior en
        //     vez de dejar sinRuta) pero que SÍ tendría ruta on-time si se ignorara la capacidad de
        //     almacén: le llegaron maletas a un almacén que las habría puesto en sobrecapacidad.
        //     Se evalúa ANTES de reconstruir la espera de origen del backlog para que la ocupación
        //     no incluya a los propios sinRuta de este bloque (evita auto-bloqueo). Early-exit al
        //     primer positivo.
        boolean colapsoAlmacen = false;
        String detalleColapso = null;
        for (LuggageBatch b : finalBatches) {
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (enrutada && b.isCumpleSLA()) continue;   // on-time: ningún almacén lo bloqueó
            if (enrutador.sinRutaPorAlmacenLleno(b)) {
                colapsoAlmacen = true;
                detalleColapso = b.getId() + " " + b.getOriginCode() + "->" + b.getDestCode()
                        + (enrutada ? " (desviado tardío por almacén lleno)" : "");
                break;
            }
        }

        // 4. Reabastecer el backlog con los batches que aún pendientes/críticos.
        if (backlog != null) {
            boolean motorAco = MOTOR_ACO.equalsIgnoreCase(motor);
            double umbralSlack = props.getBacklog().getUmbralReplanificacionSlack();
            for (LuggageBatch b : finalBatches) {
                boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
                if (!enrutada) {
                    backlog.addSinRuta(b);
                } else if (!motorAco && b.isCumpleSLA() && b.getSlaSlackRatio() < umbralSlack) {
                    // Próximo a tardar — candidato a replanificación preventiva.
                    backlog.addReplanificable(b);
                }
            }
            // Fase Origen-B — recontabiliza la ocupación de origen de los envíos sinRuta que
            // siguen esperando en el backlog (avanza el reloj UTC y reconstruye desde cero).
            // Devuelve el primer envío que NO cupo en su origen ⇒ colapso por almacén de origen
            // (cubre el caso "demasiados en origen", que la detección por rutas no capta).
            LuggageBatch origenDesbordado = enrutador.reconstruirEsperaOrigenBacklog(
                    backlog.peekPendientes(), bloqueBatches);
            if (origenDesbordado != null && !colapsoAlmacen) {
                colapsoAlmacen = true;
                detalleColapso = "origen lleno " + origenDesbordado.getId()
                        + " @" + origenDesbordado.getOriginCode();
            }
        }

        // 5. Acumular para auditoría: cada batch queda con su última asignación
        //    (sobreescribe entradas anteriores si volvió por el backlog).
        if (auditAcc != null) {
            for (LuggageBatch b : finalBatches) auditAcc.put(batchAuditKey(b), b);
        }

        // Reportar Ta como variable fija del modelo (taMs = ta-segundos * 1000).
        // Si Ta no está configurado, queda el legacy (taMs = tiempo medido).
        ctx.marcarFin(taFijoMs);

        SimulacionResponse.BloqueSimulacion bloque = new SimulacionResponse.BloqueSimulacion();
        bloque.setHoraInicio(ctx.scStart.toString());
        bloque.setHoraFin(ctx.scEnd.toString());
        // Rango UTC real del bloque = min/max de los registroUtc de sus asignaciones. No se deriva
        // de scStart/scEnd porque esos están en el eje de registro local (mezcla husos).
        String[] rangoUtc = rangoUtcRegistros(asignaciones);
        bloque.setHoraInicioUtc(rangoUtc[0]);
        bloque.setHoraFinUtc(rangoUtc[1]);
        bloque.setMaletasProcesadas(finalBatches.size());
        bloque.setMaletasEnrutadas(enrutadas);
        bloque.setAsignaciones(asignaciones);
        // El bloque ya fue commiteado a la ocupación global ⇒ la telemetría reporta el ACUMULADO
        // (global incluye este bloque) de cada recurso tocado, no el delta del bloque.
        bloque.setCargasVuelos(buildCargasVuelos(telemetryFlight, graph, enrutador));
        bloque.setOcupacionAlmacenes(buildOcupacionAlmacenes(telemetryAirport, graph, enrutador));
        bloque.setBloqueIdx(ctx.bloqueIdx);
        bloque.setTaMs(ctx.taMs);
        bloque.setScMinutos(ctx.scMinutos);
        llenarAcumuladosFisicos(bloque, auditAcc);

        // Alerta de colapso INMINENTE (pre-colapso): precursores de los 2 criterios reales.
        var pre = enrutador.evaluarPreColapso(
                telemetryAirport, backlog != null ? backlog.peekPendientes() : java.util.List.of());
        com.tasfb2b.planificador.dto.AlertaColapso alerta = construirAlertaColapso(pre, ctx.bloqueIdx);

        // Desborde DURO: ocupación real > 100% en algún slot de almacén tocado este bloque. No
        // debería ocurrir (toda ruta valida su estadía completa antes de aplicarse al bloque),
        // pero si ocurre la simulación no puede seguir planificando sobre un almacén físicamente
        // imposible: se detiene de inmediato como colapso.
        if (!colapsoAlmacen && pre.utilAlmacenMax() > 1.0) {
            colapsoAlmacen = true;
            detalleColapso = "desborde de almacén " + pre.almacenCritico() + " al "
                    + Math.round(pre.utilAlmacenMax() * 100.0) + "% de capacidad";
        }

        return new ResultadoVentana(bloque, finalBatches.size(), enrutadas, sinRuta, cumpleSLA, tardadas, maletas,
                colapsoAlmacen, detalleColapso, alerta);
    }

    /**
     * Traduce las señales crudas de pre-colapso a una {@link com.tasfb2b.planificador.dto.AlertaColapso}
     * (nivel = máximo entre la señal de almacén y la de backlog) aplicando los umbrales configurables.
     */
    private com.tasfb2b.planificador.dto.AlertaColapso construirAlertaColapso(
            GreedyRepairOperator.PreColapso pre, int bloque) {
        var cfg = props.getAlertaColapso();
        // Nivel por almacén.
        String nivelAlmacen = com.tasfb2b.planificador.dto.AlertaColapso.VERDE;
        if (pre.utilAlmacenMax() >= cfg.getAlmacenRojo()) nivelAlmacen = com.tasfb2b.planificador.dto.AlertaColapso.ROJO;
        else if (pre.utilAlmacenMax() >= cfg.getAlmacenAmbar()) nivelAlmacen = com.tasfb2b.planificador.dto.AlertaColapso.AMBAR;
        // Nivel por backlog (holgura SLA restante baja = urgente).
        String nivelBacklog = com.tasfb2b.planificador.dto.AlertaColapso.VERDE;
        if (pre.envioUrgente() != null) {
            if (pre.holguraSlaMin() <= cfg.getSlaRestanteRojo()) nivelBacklog = com.tasfb2b.planificador.dto.AlertaColapso.ROJO;
            else if (pre.holguraSlaMin() <= cfg.getSlaRestanteAmbar()) nivelBacklog = com.tasfb2b.planificador.dto.AlertaColapso.AMBAR;
        }
        String nivel = nivelMax(nivelAlmacen, nivelBacklog);

        StringBuilder msg = new StringBuilder();
        if (!com.tasfb2b.planificador.dto.AlertaColapso.VERDE.equals(nivelAlmacen)) {
            msg.append(String.format("almacén %s al %.0f%% de capacidad",
                    pre.almacenCritico(), pre.utilAlmacenMax() * 100));
        }
        if (!com.tasfb2b.planificador.dto.AlertaColapso.VERDE.equals(nivelBacklog)) {
            if (msg.length() > 0) msg.append(" | ");
            msg.append(String.format("envío %s al %.0f%% de su SLA en backlog",
                    pre.envioUrgente(), Math.max(0, pre.holguraSlaMin()) * 100));
        }
        if (msg.length() == 0) msg.append("Sin riesgo de colapso");

        return new com.tasfb2b.planificador.dto.AlertaColapso(
                nivel, msg.toString(), bloque,
                pre.utilAlmacenMax(), pre.almacenCritico(), pre.holguraSlaMin(), pre.envioUrgente());
    }

    /**
     * Loguea en consola la alerta de colapso inminente SOLO cuando el nivel cambia a/entre
     * AMBAR/ROJO (evita spam por bloque). Devuelve el nivel actual para el siguiente bloque.
     */
    private String avisarColapsoInminente(String escenario, AlertaColapso alerta, int bloque, String nivelPrevio) {
        if (alerta == null) return nivelPrevio;
        String nivel = alerta.getNivel();
        if (!AlertaColapso.VERDE.equals(nivel) && !nivel.equals(nivelPrevio)) {
            log.warn("{} ⚠ COLAPSO INMINENTE [{}] bloque {} — {}", escenario, nivel, bloque, alerta.getMensaje());
        }
        return nivel;
    }

    private static String nivelMax(String a, String b) {
        if (com.tasfb2b.planificador.dto.AlertaColapso.ROJO.equals(a)
                || com.tasfb2b.planificador.dto.AlertaColapso.ROJO.equals(b))
            return com.tasfb2b.planificador.dto.AlertaColapso.ROJO;
        if (com.tasfb2b.planificador.dto.AlertaColapso.AMBAR.equals(a)
                || com.tasfb2b.planificador.dto.AlertaColapso.AMBAR.equals(b))
            return com.tasfb2b.planificador.dto.AlertaColapso.AMBAR;
        return com.tasfb2b.planificador.dto.AlertaColapso.VERDE;
    }

    /**
     * Construye la lista de {@link TemporalContext} que cubre todo el dataset cargado.
     * Cada bloque cubre {@code Sc = K*Sa} minutos en el eje de datos.
     */
    private List<TemporalContext> construirPlanBloques(int k) {
        return construirPlanBloques(k, null, null, null);
    }

    /**
     * Variante con fecha de inicio arbitraria.
     */
    private List<TemporalContext> construirPlanBloques(int k, LocalDateTime fechaInicio) {
        return construirPlanBloques(k, fechaInicio, null, null);
    }

    /**
     * Variante con override de Sa y duración en días.
     *
     * <p>Si {@code saMinOverride} es null, se usa {@code props.scenario.sa-minutos}.
     * Si {@code diasOverride} es null, se usa el legacy {@code max-ventanas} del yaml.
     * Si {@code diasOverride > 0}, se calcula dinámicamente:
     * {@code ventanasTotales = (dias · 24 · 60) / sa}.
     *
     * <p>El inicio efectivo se alinea hacia abajo al múltiplo de Sa más cercano
     * para que el {@code subMap} del {@code DataLoader} encaje con ventanas existentes.
     */
    private List<TemporalContext> construirPlanBloques(int k,
                                                        LocalDateTime fechaInicio,
                                                        Integer saMinOverride,
                                                        Integer diasOverride) {
        LocalDateTime primero = dataLoader.getPrimeraVentana();
        LocalDateTime ultimo = dataLoader.getUltimaVentana();
        if (primero == null || ultimo == null) return Collections.emptyList();

        int saMin = (saMinOverride != null && saMinOverride > 0)
                ? saMinOverride
                : props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);

        // Resolver inicio: clamp al rango [primero, ultimo) y alinear a Sa.
        LocalDateTime inicio = primero;
        if (fechaInicio != null) {
            if (fechaInicio.isBefore(primero)) {
                log.warn("fechaInicio={} < primera ventana del dataset {} — usando primera",
                        fechaInicio, primero);
            } else if (!fechaInicio.isBefore(ultimo.plusMinutes(saMin))) {
                log.warn("fechaInicio={} fuera del rango (último={}) — usando primera",
                        fechaInicio, ultimo);
            } else {
                inicio = alinearASa(fechaInicio, primero, saMin);
            }
        }

        // Cálculo dinámico de ventanas totales:
        //   - Si dias > 0:                ventanasTotales = (dias · 24 · 60) / sa
        //   - Si no, fallback a max-ventanas (legacy global).
        long ventanasTotales;
        if (diasOverride != null && diasOverride > 0) {
            ventanasTotales = (long) diasOverride * 24L * 60L / saMin;
        } else {
            ventanasTotales = props.getScenario().getMaxVentanas();
        }

        // Final del horizonte de simulación.
        // Si ventanasTotales > 0, el horizonte es inicio + ventanasTotales · Sa.
        // Si no, cubrimos hasta el final del dataset cargado.
        LocalDateTime fin;
        if (ventanasTotales > 0) {
            fin = inicio.plusMinutes(ventanasTotales * saMin);
            // No exceder el final del dataset (última ventana incluida).
            LocalDateTime topeDataset = ultimo.plusMinutes(saMin);
            if (fin.isAfter(topeDataset)) fin = topeDataset;
        } else {
            fin = ultimo.plusMinutes(saMin);
        }

        if (!inicio.isBefore(fin)) {
            log.warn("Plan vacío: inicio={} >= fin={}", inicio, fin);
            return Collections.emptyList();
        }

        log.info("Plan de bloques: inicio={} fin={} K={} Sa={}min Sc={}min ventanas={}{}",
                inicio, fin, k, saMin, scMin, ventanasTotales,
                diasOverride != null ? " (dias=" + diasOverride + ")" : "");

        List<TemporalContext> plan = new ArrayList<>();
        LocalDateTime scStart = inicio;
        int idx = 0;
        while (scStart.isBefore(fin)) {
            LocalDateTime scEnd = scStart.plusMinutes(scMin);
            if (scEnd.isAfter(fin)) scEnd = fin;
            plan.add(new TemporalContext(scStart, scEnd, scMin, saMin, k, idx++));
            scStart = scEnd;
        }
        return plan;
    }

    /**
     * Construye el plan de warm-up: bloques desde la primera ventana del
     * dataset hasta {@code fechaInicio} (excluido), alineados a Sa·K.
     *
     * <p>Permite que el motor "alcance" un estado realista cuando el usuario
     * pide arrancar la simulación visible varios días/semanas/meses adelante
     * de la primera ventana del dataset. Los bloques resultantes se procesan
     * sin publicarse al front y sin padding-sleep — solo sirven para acumular
     * backlog, ocupaciones de vuelo y almacén.
     *
     * <p>Devuelve lista vacía si {@code fechaInicio} es null, está fuera del
     * rango del dataset, o no deja al menos un bloque completo de warm-up.
     */
    private List<TemporalContext> construirPlanWarmup(int k,
                                                       LocalDateTime fechaInicio,
                                                       Integer saMinOverride) {
        if (fechaInicio == null) return Collections.emptyList();
        LocalDateTime primero = dataLoader.getPrimeraVentana();
        LocalDateTime ultimo  = dataLoader.getUltimaVentana();
        if (primero == null || ultimo == null) return Collections.emptyList();

        int saMin = (saMinOverride != null && saMinOverride > 0)
                ? saMinOverride
                : props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);

        // fechaInicio fuera de rango → sin warm-up útil.
        if (!fechaInicio.isAfter(primero)) return Collections.emptyList();
        if (!fechaInicio.isBefore(ultimo.plusMinutes(saMin))) return Collections.emptyList();

        LocalDateTime fin = alinearASa(fechaInicio, primero, saMin);
        if (!primero.isBefore(fin)) return Collections.emptyList();

        log.info("Plan warm-up: inicio={} fin={} K={} Sa={}min Sc={}min",
                primero, fin, k, saMin, scMin);

        List<TemporalContext> plan = new ArrayList<>();
        LocalDateTime scStart = primero;
        int idx = 0;
        while (scStart.isBefore(fin)) {
            LocalDateTime scEnd = scStart.plusMinutes(scMin);
            if (scEnd.isAfter(fin)) scEnd = fin;
            plan.add(new TemporalContext(scStart, scEnd, scMin, saMin, k, idx++));
            scStart = scEnd;
        }
        return plan;
    }

    /**
     * Alinea {@code t} hacia abajo a un múltiplo de Sa minutos contado desde
     * {@code base}. Garantiza que cada bloque consume datos de ventanas
     * existentes (la lista plana ordenada del {@code DataLoader}).
     */
    private static LocalDateTime alinearASa(LocalDateTime t, LocalDateTime base, int saMin) {
        long minutosDesdeBase = java.time.Duration.between(base, t).toMinutes();
        long alineado = (minutosDesdeBase / saMin) * saMin;
        return base.plusMinutes(alineado);
    }

    /**
     * Construye los DTOs de asignación para una lista de batches ya ruteados.
     * Visible a nivel de paquete para pruebas de la conversión a UTC (husos).
     */
    List<SimulacionResponse.AsignacionMaleta> buildAsignaciones(List<LuggageBatch> batches) {
        return batches.stream().map(b -> {
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            SimulacionResponse.AsignacionMaleta asig = new SimulacionResponse.AsignacionMaleta();
            asig.setBatchId(b.getId());
            asig.setOrigen(b.getOriginCode());
            asig.setDestino(b.getDestCode());
            asig.setCantidad(b.getQuantity());
            asig.setEnrutada(enrutada);
            asig.setCumpleSLA(b.isCumpleSLA());
            asig.setRutaVuelos(enrutada
                    ? b.getAssignedRoute().stream().map(e -> e.id).collect(Collectors.toList())
                    : Collections.emptyList());

            // El motor ya opera en UTC (AlgorithmMapper normaliza vuelos y readyTime con el
            // offset de cada aeropuerto). Por eso readyTime/departures YA son UTC; la hora de
            // pared local se reconstruye sumando el offset (local = utc + offset).
            LocalDateTime ready = b.getReadyTime();
            if (ready != null) {
                long readyUtcMin = toEpochMin(ready);
                asig.setRegistroUtc(epochMinToIso(readyUtcMin));
                asig.setRegistroLocal(epochMinToIso(readyUtcMin + offsetHoras(b.getOriginCode()) * 60L));
            }

            List<SimulacionResponse.TramoRuta> tramos = Collections.emptyList();
            if (enrutada && b.getAssignedDepartures() != null && !b.getAssignedDepartures().isEmpty()) {
                var route = b.getAssignedRoute();
                var deps = b.getAssignedDepartures();
                tramos = new ArrayList<>();
                for (int ti = 0; ti < route.size(); ti++) {
                    var edge = route.get(ti);
                    long depMin = deps.get(ti);          // UTC (epoch-min)
                    long arrMin = depMin + edge.durationMinutes; // UTC; duración real de vuelo
                    String origen  = edge.from != null ? edge.from.code : "";
                    String destino = edge.to != null ? edge.to.code : "";
                    SimulacionResponse.TramoRuta tr = new SimulacionResponse.TramoRuta();
                    tr.setVueloId(edge.id);
                    tr.setOrigen(origen);
                    tr.setDestino(destino);
                    // UTC = directo (el motor ya lo entrega normalizado).
                    tr.setSalidaUtc(epochMinToIso(depMin));
                    tr.setLlegadaUtc(epochMinToIso(arrMin));
                    // Local = hora de pared de cada extremo (origen para salida, destino para llegada).
                    tr.setSalidaLocal(epochMinToIso(depMin + offsetHoras(origen) * 60L));
                    tr.setLlegadaLocal(epochMinToIso(arrMin + offsetHoras(destino) * 60L));
                    // Duración real del vuelo (UTC). El front debe usar esto, NO restar los *Local.
                    tr.setDuracionMin(edge.durationMinutes);
                    tramos.add(tr);
                }
            }
            asig.setTramos(tramos);
            return asig;
        }).collect(Collectors.toList());
    }

    // =========================================================
    // Diagnóstico
    // =========================================================
    private void logDiagnosticos(Map<String, int[]> odStats, Graph graph, GreedyRepairOperator enrutador) {
        log.info("===== DIAGNÓSTICO =====");
        log.info("Top 25 pares O→D sin ruta:");
        odStats.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) ->
                        e.getValue()[0] - e.getValue()[1]).reversed())
                .limit(25)
                .forEach(e -> {
                    int tot = e.getValue()[0], sinR = tot - e.getValue()[1];
                    log.info("  {} | total={} sinRuta={} ({}%)", e.getKey(), tot, sinR,
                            tot > 0 ? sinR * 100 / tot : 0);
                });

        log.info("Conectividad (vuelos de salida por aeropuerto):");
        int sinSalida = 0;
        for (String code : graph.nodes.keySet()) {
            int sal = graph.getNeighbors(code).size();
            if (sal == 0) {
                log.warn("  AISLADO: {}", code);
                sinSalida++;
            } else log.info("  {} → {} vuelos", code, sal);
        }
        if (sinSalida == 0) log.info("  Todos los aeropuertos tienen salidas.");
        enrutador.logEstadisticasCapacidad();
        log.info("=======================");
    }

    private SimulacionResponse.Metricas metricasDesdeBloques(List<SimulacionResponse.BloqueSimulacion> bloques) {
        SimulacionResponse.Metricas m = new SimulacionResponse.Metricas();
        if (bloques == null || bloques.isEmpty()) return m;

        int procesadas = 0;
        int enrutadas = 0;
        int cumpleSla = 0;
        long maletas = 0L;
        long taMin = Long.MAX_VALUE;
        long taMax = 0L;
        long taTotal = 0L;
        int taCount = 0;

        for (SimulacionResponse.BloqueSimulacion bloque : bloques) {
            procesadas += bloque.getMaletasProcesadas();
            enrutadas += bloque.getMaletasEnrutadas();
            if (bloque.getAsignaciones() != null) {
                for (SimulacionResponse.AsignacionMaleta a : bloque.getAsignaciones()) {
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

    private static Map<String, Object> tasas(SimulacionResponse.Metricas m) {
        Map<String, Object> tasas = new LinkedHashMap<>();
        int procesadas = m != null ? m.getProcesadas() : 0;
        tasas.put("enrutamientoPct", porcentaje(m != null ? m.getEnrutadas() : 0, procesadas));
        tasas.put("sinRutaPct", porcentaje(m != null ? m.getSinRuta() : 0, procesadas));
        tasas.put("cumpleSlaPct", porcentaje(m != null ? m.getCumpleSLA() : 0, procesadas));
        tasas.put("tardadasPct", porcentaje(m != null ? m.getTardadas() : 0, procesadas));
        return tasas;
    }

    private static Map<String, Object> ultimoBloqueResumen(JobState job) {
        if (job == null || job.bloquesPublicados() == 0) return null;
        List<SimulacionResponse.BloqueSimulacion> ultimos =
                job.bloquesDesde(job.bloquesPublicados() - 1);
        if (ultimos.isEmpty()) return null;
        SimulacionResponse.BloqueSimulacion b = ultimos.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bloqueIdx", b.getBloqueIdx());
        out.put("horaInicio", b.getHoraInicio());
        out.put("horaFin", b.getHoraFin());
        out.put("maletasProcesadas", b.getMaletasProcesadas());
        out.put("maletasEnrutadas", b.getMaletasEnrutadas());
        out.put("maletasProcesadasAcum", b.getMaletasProcesadasAcum());
        out.put("maletasEnrutadasAcum", b.getMaletasEnrutadasAcum());
        out.put("maletasEntregadasAcum", b.getMaletasEntregadasAcum());
        out.put("taMs", b.getTaMs());
        out.put("scMinutos", b.getScMinutos());
        return out;
    }

    private List<SimulacionResponse.CargaVuelo> cargasDelBloque(SimulacionResponse.BloqueSimulacion bloque) {
        if (bloque == null) return List.of();
        if (bloque.getCargasVuelos() != null && !bloque.getCargasVuelos().isEmpty()) {
            return bloque.getCargasVuelos();
        }
        return derivarCargasDesdeAsignaciones(bloque);
    }

    private List<SimulacionResponse.OcupacionAlmacen> ocupacionesDelBloque(SimulacionResponse.BloqueSimulacion bloque) {
        if (bloque == null) return List.of();
        if (bloque.getOcupacionAlmacenes() != null && !bloque.getOcupacionAlmacenes().isEmpty()) {
            return bloque.getOcupacionAlmacenes();
        }
        return derivarOcupacionesDesdeAsignaciones(bloque);
    }

    // Package-private para tests (telemetría por bloque vs ocupación acumulada).
    // El mapa del bloque selecciona QUÉ vuelos-día reportar (los tocados en este bloque); la carga
    // reportada es la ACUMULADA global del enrutador, que tras commitBlock ya incluye el bloque.
    // Sin enrutador (legacy/tests) se reporta el delta del bloque, como antes del fix.
    List<SimulacionResponse.CargaVuelo> buildCargasVuelos(Map<Long, Integer> blockFlight, Graph graph,
                                                          GreedyRepairOperator enrutador) {
        if (blockFlight == null || blockFlight.isEmpty() || graph == null) return List.of();

        Map<Integer, Edge> edgesByIdx = new HashMap<>();
        for (Edge edge : graph.edges) edgesByIdx.put(edge.idx, edge);

        List<SimulacionResponse.CargaVuelo> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : blockFlight.entrySet()) {
            int carga = enrutador != null
                    ? enrutador.ocupacionGlobalVuelo(entry.getKey())
                    : entry.getValue();
            if (carga <= 0) continue;
            Edge edge = edgesByIdx.get(resourceIdx(entry.getKey()));
            if (edge == null) continue;

            LocalDateTime salida = LocalDate.ofEpochDay(epochDay(entry.getKey()))
                    .atStartOfDay()
                    .plusMinutes(edge.depMinuteOfDay);
            LocalDateTime llegada = salida.plusMinutes(edge.durationMinutes);

            SimulacionResponse.CargaVuelo dto = new SimulacionResponse.CargaVuelo();
            dto.setVueloId(edge.id);
            dto.setOrigen(edge.from != null ? edge.from.code : "");
            dto.setDestino(edge.to != null ? edge.to.code : "");
            dto.setFechaSalida(salida.toString());
            dto.setFechaLlegada(llegada.toString());
            dto.setCapacidadMaxima(edge.capacity);
            dto.setCargaAsignada(carga);
            completarCargaVuelo(dto);
            out.add(dto);
        }
        out.sort(Comparator.comparing(SimulacionResponse.CargaVuelo::getFechaSalida)
                .thenComparing(SimulacionResponse.CargaVuelo::getVueloId));
        return out;
    }

    // Package-private para tests (telemetría por bloque vs ocupación acumulada).
    // El mapa del bloque selecciona QUÉ slots reportar (los tocados en este bloque); la ocupación
    // reportada es la ACUMULADA global del enrutador (tras commitBlock incluye el bloque, más la
    // espera en origen del backlog). Sin enrutador (legacy/tests) se reporta el delta del bloque.
    List<SimulacionResponse.OcupacionAlmacen> buildOcupacionAlmacenes(Map<Long, Integer> blockAirport,
                                                                       Graph graph,
                                                                       GreedyRepairOperator enrutador) {
        if (blockAirport == null || blockAirport.isEmpty() || graph == null) return List.of();

        Map<Integer, Node> nodesByIdx = new HashMap<>();
        for (Node node : graph.nodes.values()) nodesByIdx.put(node.idx, node);

        // Las claves de almacén son por SLOT de 60 min (slotKey(nodeIdx, epochMin/60)). Decodificamos
        // el slot → día y agregamos por (aeropuerto, día) tomando el PICO concurrente de ocupación
        // (la métrica con sentido frente a la capacidad: cuántas maletas hubo a la vez ese día).
        Map<Long, Integer> picoPorAeroDia = new LinkedHashMap<>();   // clave (nodeIdx, epochDay) → pico
        for (Map.Entry<Long, Integer> entry : blockAirport.entrySet()) {
            int ocupacion = enrutador != null
                    ? enrutador.ocupacionGlobalAlmacen(entry.getKey())
                    : entry.getValue();
            if (ocupacion <= 0) continue;
            int nodeIdx = resourceIdx(entry.getKey());
            long slot = entry.getKey() & FlightKeyEncoder.DAY_MASK;   // índice de slot (epochMin/60)
            long epochDia = (slot * GreedyRepairOperator.STORAGE_SLOT_MIN) / FlightKeyEncoder.DAY_MIN;
            long claveAeroDia = (((long) nodeIdx) << FlightKeyEncoder.DAY_BITS) | (epochDia & FlightKeyEncoder.DAY_MASK);
            picoPorAeroDia.merge(claveAeroDia, ocupacion, Integer::max);
        }

        List<SimulacionResponse.OcupacionAlmacen> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : picoPorAeroDia.entrySet()) {
            Node node = nodesByIdx.get(resourceIdx(entry.getKey()));
            if (node == null) continue;

            SimulacionResponse.OcupacionAlmacen dto = new SimulacionResponse.OcupacionAlmacen();
            dto.setAeropuerto(node.code);
            dto.setFecha(LocalDate.ofEpochDay(epochDay(entry.getKey())).toString());
            dto.setCapacidadMaxima(node.capacity);
            dto.setOcupacionAsignada(entry.getValue());   // pico concurrente del día
            completarOcupacionAlmacen(dto);
            out.add(dto);
        }
        out.sort(Comparator.comparing(SimulacionResponse.OcupacionAlmacen::getFecha)
                .thenComparing(SimulacionResponse.OcupacionAlmacen::getAeropuerto));
        return out;
    }

    private List<SimulacionResponse.CargaVuelo> derivarCargasDesdeAsignaciones(
            SimulacionResponse.BloqueSimulacion bloque) {
        if (bloque == null || bloque.getAsignaciones() == null) return List.of();
        Map<String, Integer> capacidades = capacidadesVuelosPorId();
        Map<String, SimulacionResponse.CargaVuelo> acc = new LinkedHashMap<>();

        for (SimulacionResponse.AsignacionMaleta asignacion : bloque.getAsignaciones()) {
            if (!asignacion.isEnrutada() || asignacion.getTramos() == null) continue;
            for (SimulacionResponse.TramoRuta tramo : asignacion.getTramos()) {
                String vueloId = safe(tramo.getVueloId());
                // Eje UTC, igual que buildCargasVuelos: el mismo campo no puede cambiar de eje
                // según el camino (principal vs fallback legacy).
                String salida = safe(tramo.getSalidaUtc());
                String key = vueloId + "|" + salida;
                SimulacionResponse.CargaVuelo dto = acc.computeIfAbsent(key, k -> {
                    SimulacionResponse.CargaVuelo nuevo = new SimulacionResponse.CargaVuelo();
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

        acc.values().forEach(PlanificadorService::completarCargaVuelo);
        return new ArrayList<>(acc.values());
    }

    private List<SimulacionResponse.OcupacionAlmacen> derivarOcupacionesDesdeAsignaciones(
            SimulacionResponse.BloqueSimulacion bloque) {
        if (bloque == null || bloque.getAsignaciones() == null) return List.of();
        Map<String, Integer> capacidades = capacidadesAlmacenPorCodigo();
        Map<String, SimulacionResponse.OcupacionAlmacen> acc = new LinkedHashMap<>();

        for (SimulacionResponse.AsignacionMaleta asignacion : bloque.getAsignaciones()) {
            if (!asignacion.isEnrutada()
                    || asignacion.getTramos() == null
                    || asignacion.getTramos().isEmpty()) continue;

            SimulacionResponse.TramoRuta ultimo =
                    asignacion.getTramos().get(asignacion.getTramos().size() - 1);
            String aeropuerto = safe(ultimo.getDestino());
            // Eje UTC: el camino principal (buildOcupacionAlmacenes) deriva la fecha del
            // almacén-día del slot UTC; el fallback debe usar el mismo eje.
            String fecha = fechaDe(ultimo.getLlegadaUtc());
            String key = aeropuerto + "|" + fecha;
            SimulacionResponse.OcupacionAlmacen dto = acc.computeIfAbsent(key, k -> {
                SimulacionResponse.OcupacionAlmacen nuevo = new SimulacionResponse.OcupacionAlmacen();
                nuevo.setAeropuerto(aeropuerto);
                nuevo.setFecha(fecha);
                nuevo.setCapacidadMaxima(capacidades.getOrDefault(aeropuerto, 0));
                return nuevo;
            });
            dto.setOcupacionAsignada(dto.getOcupacionAsignada() + asignacion.getCantidad());
        }

        acc.values().forEach(PlanificadorService::completarOcupacionAlmacen);
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

    private static void completarCargaVuelo(SimulacionResponse.CargaVuelo dto) {
        double porcentaje = porcentaje(dto.getCargaAsignada(), dto.getCapacidadMaxima());
        dto.setPorcentajeCarga(porcentaje);
        dto.setSemaforo(semaforoPorPorcentaje(porcentaje));
    }

    private static void completarOcupacionAlmacen(SimulacionResponse.OcupacionAlmacen dto) {
        double porcentaje = porcentaje(dto.getOcupacionAsignada(), dto.getCapacidadMaxima());
        dto.setPorcentajeOcupacion(porcentaje);
        dto.setSemaforo(semaforoPorPorcentaje(porcentaje));
    }

    private static class VueloUsadoAccumulator {
        private final VuelosUsadosResponse.VueloUsado row = new VuelosUsadosResponse.VueloUsado();
        private final Set<String> envioKeys = new LinkedHashSet<>();
        private final List<String> envioIds = new ArrayList<>();

        private VuelosUsadosResponse.VueloUsado toDto() {
            row.setCantidadEnvios(envioKeys.size());
            row.setEnvioIds(List.copyOf(envioIds));
            return row;
        }
    }

    private static Map<String, Object> cargaVueloToMap(SimulacionResponse.CargaVuelo c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vueloId", c.getVueloId());
        out.put("origen", c.getOrigen());
        out.put("destino", c.getDestino());
        out.put("fechaSalida", c.getFechaSalida());
        out.put("fechaLlegada", c.getFechaLlegada());
        out.put("capacidadMaxima", c.getCapacidadMaxima());
        out.put("cargaAsignada", c.getCargaAsignada());
        out.put("porcentajeCarga", c.getPorcentajeCarga());
        out.put("semaforo", c.getSemaforo());
        return out;
    }

    private static Map<String, Object> ocupacionAlmacenToMap(SimulacionResponse.OcupacionAlmacen o) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aeropuerto", o.getAeropuerto());
        out.put("fecha", o.getFecha());
        out.put("capacidadMaxima", o.getCapacidadMaxima());
        out.put("ocupacionAsignada", o.getOcupacionAsignada());
        out.put("porcentajeOcupacion", o.getPorcentajeOcupacion());
        out.put("semaforo", o.getSemaforo());
        return out;
    }

    private static boolean pasaFiltroAeropuerto(SimulacionResponse.AsignacionMaleta a, String aeropuerto) {
        if (aeropuerto.equalsIgnoreCase(safe(a.getOrigen()))
                || aeropuerto.equalsIgnoreCase(safe(a.getDestino()))) return true;
        if (a.getTramos() == null) return false;
        for (SimulacionResponse.TramoRuta tramo : a.getTramos()) {
            if (aeropuerto.equalsIgnoreCase(safe(tramo.getOrigen()))
                    || aeropuerto.equalsIgnoreCase(safe(tramo.getDestino()))) return true;
        }
        return false;
    }

    private static boolean pasaFiltroVuelo(SimulacionResponse.AsignacionMaleta a, String vueloId) {
        if (a.getTramos() == null) return false;
        for (SimulacionResponse.TramoRuta tramo : a.getTramos()) {
            if (vueloId.equalsIgnoreCase(safe(tramo.getVueloId()))) return true;
        }
        return false;
    }

    private static void acumularDemanda(Map<String, long[]> acc, String key, long cantidad) {
        long[] stats = acc.computeIfAbsent(safe(key), k -> new long[2]);
        stats[0]++;
        stats[1] += cantidad;
    }

    private static List<Map<String, Object>> demandaRows(Map<String, long[]> acc, int limite) {
        List<Map<String, Object>> rows = new ArrayList<>();
        acc.entrySet().stream()
                .sorted((a, b) -> {
                    int byMaletas = Long.compare(b.getValue()[1], a.getValue()[1]);
                    if (byMaletas != 0) return byMaletas;
                    return Long.compare(b.getValue()[0], a.getValue()[0]);
                })
                .limit(limite)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("clave", e.getKey());
                    row.put("envios", e.getValue()[0]);
                    row.put("maletas", e.getValue()[1]);
                    rows.add(row);
                });
        return rows;
    }

    private static int resourceIdx(long key) {
        return (int) (key >> FlightKeyEncoder.DAY_BITS);
    }

    private static long epochDay(long key) {
        return key & FlightKeyEncoder.DAY_MASK;
    }

    private static double porcentaje(long valor, long total) {
        if (total <= 0) return 0.0;
        return Math.round((valor * 10000.0) / total) / 100.0;
    }

    private static String semaforoPorPorcentaje(double porcentaje) {
        double ratio = porcentaje / 100.0;
        if (ratio <= CostFunction.UMBRAL_VERDE) return "VERDE";
        if (ratio <= CostFunction.UMBRAL_AMBAR) return "AMBAR";
        return "ROJO";
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

    // =========================================================
    // Helpers de respuesta
    // =========================================================
    private SimulacionResponse construirRespuestaFront(int enrutadas, long tiempoMs,
                                                       List<Vuelo> vuelosReales,
                                                       int totalBloques,
                                                       LocalDate simulationDate) {
        SimulacionResponse res = new SimulacionResponse();
        SimulacionResponse.Metricas m = new SimulacionResponse.Metricas();
        m.setEnrutadas(enrutadas);
        m.setTiempoEjecucionMs(tiempoMs);
        res.setMetricas(m);
        res.setTotalBloques(totalBloques);

        long dayShift = simulationDate != null
                ? ChronoUnit.DAYS.between(FlightParser.FLIGHT_BASE_DATE, simulationDate) : 0L;

        List<SimulacionResponse.VueloBackend> vuelosFront = new ArrayList<>();
        Map<String, SimulacionResponse.AeropuertoDTO> infoAero = new HashMap<>();
        for (Vuelo v : vuelosReales) {
            SimulacionResponse.VueloBackend vb = new SimulacionResponse.VueloBackend();
            vb.setId(vueloFrontId(v));
            vb.setOrigen(v.getOrigen());
            vb.setDestino(v.getDestino());
            vb.setFechaSalida(v.getFechaHoraSalida().plusDays(dayShift).toString());
            vb.setFechaLlegada(v.getFechaHoraLlegada().plusDays(dayShift).toString());
            vb.setCapacidadMaxima(v.getCapacidad() != null ? v.getCapacidad() : 0);
            vb.setCargaAsignada(0);
            vuelosFront.add(vb);
            agregarInfoAeropuerto(infoAero, v.getOrigen(), v.getAeropuertoOrigen());
            agregarInfoAeropuerto(infoAero, v.getDestino(), v.getAeropuertoDestino());
        }
        res.setVuelosPlaneados(vuelosFront);
        res.setAeropuertosInfo(infoAero);
        return res;
    }

    private static void llenarMetricas(SimulacionResponse.Metricas m,
                                       int envios, int enrutadas, int sinRuta,
                                       int cumpleSLA, int tardadas, long maletas,
                                       int vuelosCancelados,
                                       boolean collapso, int bloqueCollapso) {
        m.setProcesadas(envios);
        m.setEnrutadas(enrutadas);
        m.setSinRuta(sinRuta);
        m.setCumpleSLA(cumpleSLA);
        m.setTardadas(tardadas);
        m.setMaletasIndividuales(maletas);
        m.setVuelosCancelados(vuelosCancelados);
        m.setCollapsoDetectado(collapso);
        m.setBloqueColapso(bloqueCollapso);
    }

    /**
     * Llena las métricas Ta/Sa de la simulación. Marca {@code advertenciaCalibracion}
     * si Ta excedió el 90% de Sa en algún bloque (cliente debe bajar K).
     */
    private static void llenarMetricasTa(SimulacionResponse.Metricas m, TaStats stats, long saMs) {
        m.setTaMinMs(stats.min());
        m.setTaMaxMs(stats.max());
        m.setTaPromedioMs(stats.promedio());
        m.setTiempoTotalAlgMs(stats.suma());
        m.setAdvertenciaCalibracion(stats.max() > saMs * 0.9);
    }

    /**
     * Backlog con purga por SLA vencido activa (sin tope), usado por todos los
     * escenarios (G2). Cada bloque se llama {@code purgarVencidas(scNow)}: los
     * envios cuyo {@code readyTime + SLA} ya paso sin entrega on-time pasan a
     * {@code sinRutaDefinitivo} y dejan de reintentarse — esto acota el backlog y
     * libera Ta para los enrutables. En E3 ese vencimiento dispara el colapso.
     *
     * <p>El hook de descarte cierra el hueco de la liberación diferida (ver
     * {@code reencolarAfectadosPorCancelacion}): si un replanificable con ruta rota
     * por una cancelación vence ANTES de que un bloque lo reprocese, aquí se libera
     * su ocupación global ({@code releaseFromGlobal} + {@code clearRoute}) para que
     * la capacidad de los tramos posteriores y la estadía de almacén no queden
     * cobradas para siempre. Las rutas válidas (replanificables preventivos) no se
     * tocan: su entrega on-time sigue en pie y siguen contando como enrutadas.
     */
    private static BacklogManager crearBacklogConPurga(GreedyRepairOperator enrutador) {
        return new BacklogManager(0, true, b -> {
            if (enrutador.rutaUsaVueloCancelado(b)) {
                enrutador.releaseFromGlobal(b);
                b.clearRoute();
            }
        });
    }

    private static Random rngParaBloque(long seed, String motor, int bloqueIdx) {
        long mixed = seed
                ^ ((long) bloqueIdx * 0x9E3779B97F4A7C15L)
                ^ ((long) (motor != null ? motor.hashCode() : 0) << 32);
        return new Random(mixed);
    }

    private static TotalesUnicos calcularTotalesUnicos(Map<String, LuggageBatch> auditAcc) {
        if (auditAcc == null || auditAcc.isEmpty()) {
            return new TotalesUnicos(0, 0, 0, 0, 0, 0L);
        }
        int envios = auditAcc.size();
        int enrutadas = 0;
        int cumpleSLA = 0;
        long maletas = 0L;
        for (LuggageBatch b : auditAcc.values()) {
            maletas += b.getQuantity();
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (enrutada) {
                enrutadas++;
                if (b.isCumpleSLA()) cumpleSLA++;
            }
        }
        int tardadas = enrutadas - cumpleSLA;
        int sinRuta = envios - enrutadas;
        return new TotalesUnicos(envios, enrutadas, sinRuta, cumpleSLA, tardadas, maletas);
    }

    // Package-private para tests (eje temporal del corte de entregas).
    // El corte de "entregadas" es el RELOJ UTC de la simulación: el máximo readyTime (UTC,
    // normalizado por AlgorithmMapper) visto en el acumulador — el mismo concepto de reloj que
    // usa reconstruirEsperaOrigenBacklog. Antes se usaba ctx.scEnd (eje de registro LOCAL,
    // mezcla husos) comparado como si fuese UTC, lo que sesgaba la cuenta hasta ± el offset
    // horario y podía contar entregas aún no ocurridas. Con el reloj UTC la cuenta es física
    // y monótona; si no entran envíos nuevos el reloj se detiene en el último registro
    // (subcuenta conservadora, nunca cuenta una entrega futura).
    static void llenarAcumuladosFisicos(SimulacionResponse.BloqueSimulacion bloque,
                                        Map<String, LuggageBatch> auditAcc) {
        if (bloque == null || auditAcc == null || auditAcc.isEmpty()) return;

        long corteMin = Long.MIN_VALUE;
        for (LuggageBatch b : auditAcc.values()) {
            long readyMin = toEpochMin(b.getReadyTime());
            if (readyMin > corteMin) corteMin = readyMin;
        }

        long procesadas = 0L;
        long enrutadas = 0L;
        long entregadas = 0L;

        for (LuggageBatch b : auditAcc.values()) {
            long cantidad = b.getQuantity();
            procesadas += cantidad;
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (!enrutada) continue;

            enrutadas += cantidad;
            if (ultimoArriboMin(b) <= corteMin) {
                entregadas += cantidad;
            }
        }

        bloque.setMaletasProcesadasAcum(procesadas);
        bloque.setMaletasEnrutadasAcum(enrutadas);
        bloque.setMaletasEntregadasAcum(entregadas);
    }

    private static long ultimoArriboMin(LuggageBatch b) {
        if (b.getAssignedRoute() == null || b.getAssignedDepartures() == null) {
            return Long.MAX_VALUE;
        }
        int lastIdx = Math.min(b.getAssignedRoute().size(), b.getAssignedDepartures().size()) - 1;
        if (lastIdx < 0) return Long.MAX_VALUE;
        return b.getAssignedDepartures().get(lastIdx)
                + b.getAssignedRoute().get(lastIdx).durationMinutes;
    }

    private static long toEpochMin(LocalDateTime dt) {
        if (dt == null) return Long.MIN_VALUE;
        return dt.toLocalDate().toEpochDay() * 1440L + dt.getHour() * 60L + dt.getMinute();
    }

    private static String batchAuditKey(LuggageBatch b) {
        if (b == null) return "";
        return String.join("|",
                safe(b.getId()),
                safe(b.getOriginCode()),
                safe(b.getDestCode()),
                b.getReadyTime() != null ? b.getReadyTime().toString() : "",
                String.valueOf(b.getQuantity()));
    }

    private static String vueloFrontId(Vuelo v) {
        if (v == null) return "";
        if (v.getId() != null) return v.getId().toString();
        String origen = v.getAeropuertoOrigen() != null ? v.getAeropuertoOrigen().getCodigo() : safe(v.getOrigen());
        String destino = v.getAeropuertoDestino() != null ? v.getAeropuertoDestino().getCodigo() : safe(v.getDestino());
        String salida = v.getFechaHoraSalida() != null ? v.getFechaHoraSalida().toLocalTime().toString() : "";
        return origen + "-" + destino + "-" + salida;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static void llenarMetricasBacklog(SimulacionResponse.Metricas m, BacklogManager backlog) {
        m.setBacklogActual(backlog.size());
        m.setBacklogPico(backlog.picoHistorico());
        m.setSinRutaDefinitivo(backlog.sinRutaDefinitivo());
    }

    /**
     * Rango UTC real de un bloque = [min, max] de los {@code registroUtc} de sus asignaciones.
     * Devuelve {@code String[2]} (ambos null si ninguna asignación tiene registro). Los registroUtc
     * son ISO sin offset y mismo formato ({@code yyyy-MM-ddTHH:mm}), así que el orden lexicográfico
     * coincide con el cronológico. Visible a nivel de paquete para pruebas.
     */
    static String[] rangoUtcRegistros(List<SimulacionResponse.AsignacionMaleta> asignaciones) {
        String min = null, max = null;
        for (SimulacionResponse.AsignacionMaleta a : asignaciones) {
            String r = a.getRegistroUtc();
            if (r == null) continue;
            if (min == null || r.compareTo(min) < 0) min = r;
            if (max == null || r.compareTo(max) > 0) max = r;
        }
        return new String[]{min, max};
    }

    /** Convierte un epoch-min (minutos desde epoch) a un ISO datetime sin offset. */
    private static String epochMinToIso(long epochMin) {
        long epochDay = Math.floorDiv(epochMin, 1440L);
        int minuteOfDay = (int) Math.floorMod(epochMin, 1440L);
        return LocalDateTime.of(
                LocalDate.ofEpochDay(epochDay),
                LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        ).toString();
    }

    /**
     * Offset horario (GMT, en horas) del aeropuerto. El motor opera en hora local
     * "pelada"; esta tabla permite reconstruir el instante UTC real en la capa de
     * presentación. Se cachea perezosamente desde {@code dataLoader.getAeropuertos()}.
     */
    private int offsetHoras(String codigo) {
        Map<String, Integer> mapa = offsetPorCodigo;
        if (mapa == null) {
            mapa = new HashMap<>();
            for (Aeropuerto a : dataLoader.getAeropuertos()) {
                if (a.getCodigo() != null && a.getOffsetHorario() != null) {
                    mapa.put(a.getCodigo(), a.getOffsetHorario());
                }
            }
            offsetPorCodigo = mapa;
        }
        return mapa.getOrDefault(codigo, 0);
    }

    private void agregarInfoAeropuerto(Map<String, SimulacionResponse.AeropuertoDTO> map,
                                       String cod, Aeropuerto a) {
        if (!map.containsKey(cod)) {
            SimulacionResponse.AeropuertoDTO dto = new SimulacionResponse.AeropuertoDTO();
            dto.setCodigo(cod);
            dto.setLatitud(a.getLatitud());
            dto.setLongitud(a.getLongitud());
            dto.setCapacidadAlmacen(a.getCapacidad());
            map.put(cod, dto);
        }
    }

    // =========================================================
    // Clases internas de apoyo
    // =========================================================
    /**
     * Una sola línea de consola por bloque con lo relevante para seguir la
     * simulación: índice de bloque, envíos del bloque, on-time (cumpleSLA),
     * tardadas, sinRuta, Ta y backlog. Sufijo {@code COLAPSO} cuando el bloque
     * dispara el colapso logístico.
     */
    private void logBloque(String motor, int bloque, int total, int envios, int onTime,
                           int tardadas, int sinRuta, long taMs, int backlog, boolean colapso) {
        log.info("Bloque {}/{} [{}] | envíos:{} | onTime:{} | tardadas:{} | sinRuta:{} | Ta:{}ms | backlog:{}{}",
                bloque, total, motor, envios, onTime, tardadas, sinRuta, taMs, backlog,
                colapso ? " | COLAPSO" : "");
    }

    private record ResultadoVentana(
            SimulacionResponse.BloqueSimulacion bloque,
            int envios, int enrutadas, int sinRuta, int cumpleSLA, int tardadas, long maletas,
            boolean colapsoAlmacen, String detalleColapso,
            com.tasfb2b.planificador.dto.AlertaColapso alerta) {
    }

    private record TotalesUnicos(
            int envios, int enrutadas, int sinRuta, int cumpleSLA, int tardadas, long maletas) {
    }

    private List<TaStats.AuditRecord> sampleAudit(List<TaStats.AuditRecord> base, int sampleSize) {
        if (sampleSize <= 0 || sampleSize >= base.size()) {
            return base;
        }

        List<TaStats.AuditRecord> exitos = new ArrayList<>();
        List<TaStats.AuditRecord> fallos = new ArrayList<>();
        for (TaStats.AuditRecord r : base) {
            if (r.exitoso) {
                exitos.add(r);
            } else {
                fallos.add(r);
            }
        }

        Collections.shuffle(exitos, new Random(42));
        Collections.shuffle(fallos, new Random(42));

        int takeExitos = Math.min(exitos.size(), sampleSize / 2);
        int takeFallos = Math.min(fallos.size(), sampleSize - takeExitos);

        List<TaStats.AuditRecord> out = new ArrayList<>();
        out.addAll(exitos.subList(0, takeExitos));
        out.addAll(fallos.subList(0, takeFallos));

        int faltantes = sampleSize - out.size();
        if (faltantes > 0) {
            List<TaStats.AuditRecord> pool = new ArrayList<>(base);
            Collections.shuffle(pool, new Random(43));
            for (TaStats.AuditRecord r : pool) {
                if (faltantes == 0) {
                    break;
                }
                if (!out.contains(r)) {
                    out.add(r);
                    faltantes--;
                }
            }
        }
        return out;
    }

    private List<String> cargarVuelos() {

        List<String> vuelos = new ArrayList<>();

        try {
            InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("data/planes_vuelo.txt");

            if (is == null) {
                System.out.println("Archivo de vuelos no encontrado");
                return List.of();
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String linea;
            while ((linea = br.readLine()) != null) {
                if (!linea.trim().isEmpty()) {
                    vuelos.add(linea.trim());
                }
            }
            br.close();

        } catch (Exception e) {
            System.out.println("Error cargando vuelos: " + e.getMessage());
        }

        return vuelos;
    }

    private boolean cumpleEscalaMinima(List<Edge> edgesPath) {
        for (int i = 0; i < edgesPath.size() - 1; i++) {
            if (!CostFunction.tieneTiempoMinimoEscala(edgesPath.get(i), edgesPath.get(i + 1))) {
                return false;
            }
        }
        return true;
    }

    private int calcularTiempoVueloMin(List<Edge> edgesPath) {
        int total = 0;
        for (Edge edge : edgesPath) {
            total += (int) Math.max(1, Math.round(
                    CostFunction.calcularDuracionMinutos(edge.departureTime.toString(), edge.arrivalTime.toString())
            ));
        }
        return total;
    }

    private int calcularTiempoEsperaMin(List<Edge> edgesPath) {
        int espera = 0;
        for (int i = 0; i < edgesPath.size() - 1; i++) {
            int llegada = parsearMinutos(edgesPath.get(i).arrivalTime.toString());
            int salida = parsearMinutos(edgesPath.get(i + 1).departureTime.toString());
            int diff = salida - llegada;
            if (diff < 0) {
                diff += 1440;
            }
            espera += diff;
        }
        return espera;
    }

    private int parsearMinutos(String hhmm) {
        return CostFunction.hhmmAMinutos(hhmm);
    }

    private boolean sinCiclos(List<Node> path) {
        Set<String> visited = new HashSet<>();
        for (Node node : path) {
            if (!visited.add(node.code)) {
                return false;
            }
        }
        return true;
    }

    private int calcularScore(boolean sinDirecto,
                              boolean sinCiclos,
                              boolean escalaMinOk,
                              boolean capacidadVuelosOk,
                              boolean almacenDestinoOk,
                              boolean cumpleSLA,
                              int escalas,
                              int tiempoEsperaMin,
                              int slackSlaMin) {
        if (!sinDirecto || !sinCiclos || !escalaMinOk || !capacidadVuelosOk || !almacenDestinoOk || !cumpleSLA) {
            return 0;
        }
        double score = 100.0;
        int excesoEscalas = Math.max(0, escalas - 2);
        score -= excesoEscalas * 15.0;
        score -= tiempoEsperaMin * 0.05;
        if (slackSlaMin < 60) {
            score -= 20.0;
        }
        return (int) Math.max(0, Math.round(score));
    }

    private int estimarMinutosRuta(List<Edge> edgesPath) {
        if (edgesPath == null || edgesPath.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < edgesPath.size(); i++) {
            Edge edge = edgesPath.get(i);
            total += (int) Math.max(1, Math.round(
                    CostFunction.calcularDuracionMinutos(edge.departureTime.toString(), edge.arrivalTime.toString())
            ));
            if (i < edgesPath.size() - 1) {
                total += CostFunction.TIEMPO_MIN_ESCALA;
            }
        }
        return total;
    }

    /**
     * Acumulador para estadísticas de Ta (tiempo de algoritmo) entre bloques.
     */
    private static final class TaStats {
        private long min = Long.MAX_VALUE;
        private long max = 0L;
        private long suma = 0L;
        private int n = 0;

        void acumular(long taMs) {
            if (taMs < min) min = taMs;
            if (taMs > max) max = taMs;
            suma += taMs;
            n++;
        }

        long min() {
            return n == 0 ? 0 : min;
        }

        long max() {
            return max;
        }

        long suma() {
            return suma;
        }

        long promedio() {
            return n == 0 ? 0 : suma / n;
        }



        private static class PlanRequest {
            final List<String> origenes;
            final int limitePorOrigen;
            final int tickMinutosSimulacion;
            final Set<String> destinosPermitidos;

            private PlanRequest(List<String> origenes,
                                int limitePorOrigen,
                                int tickMinutosSimulacion,
                                Set<String> destinosPermitidos) {
                this.origenes = origenes;
                this.limitePorOrigen = limitePorOrigen;
                this.tickMinutosSimulacion = tickMinutosSimulacion;
                this.destinosPermitidos = destinosPermitidos;
            }

            static PlanRequest todos(int limitePorOrigen, int tickMinutosSimulacion) {
                return new PlanRequest(List.of(ORIGENES_DISPONIBLES), limitePorOrigen, tickMinutosSimulacion, null);
            }

            static PlanRequest unOrigen(String origen, int limitePorOrigen, int tickMinutosSimulacion) {
                return new PlanRequest(List.of(origen), limitePorOrigen, tickMinutosSimulacion, null);
            }

            static PlanRequest unDestino(String destino, int limitePorOrigen, int tickMinutosSimulacion) {
                return new PlanRequest(List.of(ORIGENES_DISPONIBLES), limitePorOrigen, tickMinutosSimulacion,
                        new HashSet<>(Set.of(destino)));
            }
        }

        private static class ScheduledEvent {
            final int minute;
            final Runnable action;

            ScheduledEvent(int minute, Runnable action) {
                this.minute = minute;
                this.action = action;
            }
        }

        private static class BaseRunResult {
            final ResumenPlanificacionGlobal resumen;
            final List<PlanificacionResultado> resultados;
            final List<AuditRecord> auditoria;

            BaseRunResult(ResumenPlanificacionGlobal resumen,
                          List<PlanificacionResultado> resultados,
                          List<AuditRecord> auditoria) {
                this.resumen = resumen;
                this.resultados = resultados;
                this.auditoria = auditoria;
            }
        }

        private static class AttemptResult {
            final PlanificacionResultado resultado;
            final AuditRecord auditRecord;

            AttemptResult(PlanificacionResultado resultado, AuditRecord auditRecord) {
                this.resultado = resultado;
                this.auditRecord = auditRecord;
            }
        }

        private static class AuditRecord {
            final String idEnvio;
            final String origen;
            final String destino;
            final String registroHHMM;
            final int deadlineMin;
            final boolean exitoso;
            final String motivoFalla;
            final String ruta;
            final int numTramos;
            final int numEscalas;
            final int tiempoVueloMin;
            final int tiempoEsperaMin;
            final int tiempoTotalMin;
            final int llegadaMin;
            final int slackSlaMin;
            final double costoTotal;
            final boolean cumpleSla;
            final boolean sinCiclos;
            final boolean sinDirecto;
            final boolean escalaMinOk;
            final boolean capacidadVuelosOk;
            final boolean almacenDestinoOk;
            final int scoreCalidad;

            private AuditRecord(String idEnvio,
                                String origen,
                                String destino,
                                String registroHHMM,
                                int deadlineMin,
                                boolean exitoso,
                                String motivoFalla,
                                String ruta,
                                int numTramos,
                                int numEscalas,
                                int tiempoVueloMin,
                                int tiempoEsperaMin,
                                int tiempoTotalMin,
                                int llegadaMin,
                                int slackSlaMin,
                                double costoTotal,
                                boolean cumpleSla,
                                boolean sinCiclos,
                                boolean sinDirecto,
                                boolean escalaMinOk,
                                boolean capacidadVuelosOk,
                                boolean almacenDestinoOk,
                                int scoreCalidad) {
                this.idEnvio = idEnvio;
                this.origen = origen;
                this.destino = destino;
                this.registroHHMM = registroHHMM;
                this.deadlineMin = deadlineMin;
                this.exitoso = exitoso;
                this.motivoFalla = motivoFalla;
                this.ruta = ruta;
                this.numTramos = numTramos;
                this.numEscalas = numEscalas;
                this.tiempoVueloMin = tiempoVueloMin;
                this.tiempoEsperaMin = tiempoEsperaMin;
                this.tiempoTotalMin = tiempoTotalMin;
                this.llegadaMin = llegadaMin;
                this.slackSlaMin = slackSlaMin;
                this.costoTotal = costoTotal;
                this.cumpleSla = cumpleSla;
                this.sinCiclos = sinCiclos;
                this.sinDirecto = sinDirecto;
                this.escalaMinOk = escalaMinOk;
                this.capacidadVuelosOk = capacidadVuelosOk;
                this.almacenDestinoOk = almacenDestinoOk;
                this.scoreCalidad = scoreCalidad;
            }

            static AuditRecord exitoso(String origen,
                                       EnvioDTO e,
                                       int deadlineMin,
                                       List<String> ruta,
                                       double costoTotal,
                                       int numTramos,
                                       int tiempoVueloMin,
                                       int tiempoEsperaMin,
                                       int tiempoTotalMin,
                                       int llegadaMin,
                                       int slackSlaMin,
                                       boolean cumpleSla,
                                       boolean sinCiclos,
                                       boolean sinDirecto,
                                       boolean escalaMinOk,
                                       boolean capacidadVuelosOk,
                                       boolean almacenDestinoOk,
                                       int scoreCalidad) {
                return new AuditRecord(
                        e.id,
                        origen,
                        e.destinoICAO,
                        String.format("%02d:%02d", e.horaRegistro, e.minutoRegistro),
                        deadlineMin,
                        true,
                        "",
                        String.join("->", ruta),
                        numTramos,
                        Math.max(0, numTramos - 1),
                        tiempoVueloMin,
                        tiempoEsperaMin,
                        tiempoTotalMin,
                        llegadaMin,
                        slackSlaMin,
                        costoTotal,
                        cumpleSla,
                        sinCiclos,
                        sinDirecto,
                        escalaMinOk,
                        capacidadVuelosOk,
                        almacenDestinoOk,
                        scoreCalidad
                );
            }

            static AuditRecord fallido(String origen,
                                       EnvioDTO e,
                                       int deadlineMin,
                                       String motivo,
                                       int tiempoActual) {
                return new AuditRecord(
                        e.id,
                        origen,
                        e.destinoICAO,
                        String.format("%02d:%02d", e.horaRegistro, e.minutoRegistro),
                        deadlineMin,
                        false,
                        motivo,
                        "",
                        0,
                        0,
                        0,
                        0,
                        0,
                        tiempoActual,
                        deadlineMin - tiempoActual,
                        0.0,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        0
                );
            }
        }
    }
}
