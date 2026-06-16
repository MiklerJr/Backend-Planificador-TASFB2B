package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.algorithm.alns.*;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.AlertaColapso;
import com.tasfb2b.planificador.dto.AuditoriaEnvio;
import com.tasfb2b.planificador.dto.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.EjecucionParams;
import com.tasfb2b.planificador.dto.*;
import com.tasfb2b.planificador.dto.VueloCancelado;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Envio;
import com.tasfb2b.planificador.model.Vuelo;
import com.tasfb2b.planificador.dto.VuelosUsadosResponse;
import com.tasfb2b.planificador.util.AlgorithmMapper;
import com.tasfb2b.planificador.util.DataLoader;
import com.tasfb2b.planificador.util.FlightParser;
import com.tasfb2b.planificador.util.SimulacionFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.io.IOException;
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
    /** Fase 5a: persistencia de soluciones por bloque (ruta_asignada/tramo_ruta), serializada. */
    private final PersistenciaSolucionService persistencia;
    /** Fase 5b: lee la solución de BD (ZIP de auditoría y cancelación) para no retenerla en RAM. */
    private final SolucionBdReader solucionBdReader;

    public static final String MOTOR_ALNS = "alns";
    public static final String MOTOR_ACO  = "aco";

    /** Fase T (N3): candidatos por clave al pre-calentar esqueletos (= GROUP_ROUTE_CANDIDATES del hot-path). */
    private static final int PREWARM_ROUTE_CANDIDATES = 5;

    // Cache perezosa código ICAO → offset horario (GMT), para reconstruir UTC real en el DTO.
    private volatile Map<String, Integer> offsetPorCodigo = null;

    // ── ESTADO ESCENARIOS ALNS ──────────────────────────────────────────
    // (El modo E1 incremental paso a paso y su estado sc1* fueron eliminados: E1 corre
    //  únicamente como job asíncrono, igual que E2/E3.)
    private volatile List<BloqueSimulacion> bloquesCacheados = null;

    // ── CONSTRUCTOR UNIFICADO (VITAL PARA SPRING BOOT) ──────────────────
    @org.springframework.beans.factory.annotation.Autowired
    public PlanificadorService(DataLoader dataLoader,
                               AlgorithmMapper mapper,
                               PlanificadorProperties props,
                               JobsRegistry jobs,
                               AuditoriaService auditoria,
                               AcoBlockEngine acoEngine,
                               PersistenciaSolucionService persistencia,
                               SolucionBdReader solucionBdReader) {
        this.dataLoader = dataLoader;
        this.mapper = mapper;
        this.props = props;
        this.jobs = jobs;
        this.auditoria = auditoria;
        this.acoEngine = acoEngine;
        this.persistencia = persistencia;
        this.solucionBdReader = solucionBdReader;
    }

    /**
     * Constructor de conveniencia para tests (mismo paquete): omite la persistencia/lectura reales
     * usando instancias no-op (JdbcTemplate y txManager null ⇒ nunca llega a persistir; sus métodos
     * quedan en no-op por sus try/catch). Evita tocar los tests que construyen el servicio con 7
     * argumentos.
     */
    PlanificadorService(DataLoader dataLoader, AlgorithmMapper mapper, PlanificadorProperties props,
                        JobsRegistry jobs, AuditoriaService auditoria,
                        AcoBlockEngine acoEngine) {
        this(dataLoader, mapper, props, jobs, auditoria, acoEngine,
                new PersistenciaSolucionService(null, null), new SolucionBdReader(null, null, null));
    }


    // =========================================================
    // Lanzadores async (escenarios 2 y 3)
    // =========================================================

    /**
     * Lanza el escenario 2 con todos los parámetros de {@link EjecucionParams}.
     * Cualquier campo null se completa con el default del yaml.
     *
     * <p><b>K es FIJO en el escenario 2 (regla de negocio: 144)</b>: se ignora cualquier
     * {@code params.k} entrante y se usa siempre el del yaml.
     */
    public JobState iniciarEscenario2Async(EjecucionParams params) {
        if (params == null) params = new EjecucionParams();
        int k = props.getScenario().getKDefault2();   // K fijo del escenario, no negociable
        String motorRes = resolverMotor(params.getMotor());
        long seedRes = resolverSeed(params.getSeed());

        JobState job = jobs.crear("2", k);
        job.algoritmo = motorRes;
        job.seed = seedRes;
        job.fechaInicio = params.getFechaInicio();
        // Persistir los overrides para poder reiniciar idéntico (ver reiniciarJob).
        job.saMin = params.getSaMin();
        job.taSegundos = params.getTaSegundos();
        job.dias = params.getDias();
        job.procesamientoPrevio = params.isProcesamientoPrevio();

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
     * <b>K es FIJO en el escenario 3 (regla de negocio: 144)</b>: se resuelve del yaml.
     */
    public JobState iniciarEscenario3Async(double umbralColapso, String motor, Long seed) {
        return iniciarEscenario3Async(umbralColapso, motor, seed, null);
    }

    /** Variante con {@code fechaInicio}: warm-up (Ta sin sleep) hasta esa fecha y E3 desde ahí. */
    public JobState iniciarEscenario3Async(double umbralColapso, String motor, Long seed,
                                           LocalDateTime fechaInicio) {
        int k = props.getScenario().getKDefault3();   // K fijo del escenario, no negociable
        String motorRes = resolverMotor(motor);
        long seedRes = resolverSeed(seed);
        JobState job = jobs.crear("3", k);
        job.algoritmo = motorRes;
        job.seed = seedRes;
        job.fechaInicio = fechaInicio;
        job.umbralColapso = umbralColapso;   // persistir para reiniciar idéntico (ver reiniciarJob)
        jobs.ejecutar(job, () -> {
            SimulacionResponse res = ejecutarHastaColapso(k, umbralColapso, job, motorRes, seedRes, fechaInicio);
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
        return iniciarEscenario1Async(motor, seed, null);
    }

    /** Variante con {@code fechaInicio}: warm-up (Ta sin sleep) hasta esa fecha y E1 desde ahí. */
    public JobState iniciarEscenario1Async(String motor, Long seed, LocalDateTime fechaInicio) {
        String motorRes = resolverMotor(motor);
        long seedRes = resolverSeed(seed);
        int k = props.getScenario().getKDefault1();
        JobState job = jobs.crear("1", k);
        job.algoritmo = motorRes;
        job.seed = seedRes;
        job.fechaInicio = fechaInicio;
        jobs.ejecutar(job, () -> {
            SimulacionResponse res = ejecutarEscenario1(job, motorRes, seedRes, fechaInicio);
            job.resultado = res;
        });
        return job;
    }

    /**
     * Valida los parámetros de arranque de un escenario ANTES de encolar el job. Devuelve un
     * mensaje de error legible (para un 400 del controller) o {@code null} si todo es válido.
     * Tolerante a dataset no cargado (tests): en ese caso no valida el rango de fechaInicio.
     */
    public String validarParametrosEscenario(Integer k, Integer sa, Integer ta, LocalDateTime fechaInicio) {
        if (k != null && k < 1) {
            return "k debe ser >= 1 (recibido: " + k + ")";
        }
        if (sa != null && sa <= 0) {
            return "sa (minutos) debe ser > 0 (recibido: " + sa + ")";
        }
        if (ta != null && ta <= 0) {
            return "ta (segundos) debe ser > 0 (recibido: " + ta + ")";
        }
        if (fechaInicio != null && dataLoader != null) {
            LocalDateTime primera = dataLoader.getPrimeraVentana();
            LocalDateTime ultima = dataLoader.getUltimaVentana();
            if (primera != null && ultima != null
                    && (fechaInicio.isBefore(primera) || !fechaInicio.isBefore(ultima))) {
                return "fechaInicio fuera del rango del dataset [" + primera + ", " + ultima + ")";
            }
        }
        return null;
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

    /**
     * Reinicia un job: detiene la ejecución en curso (si está activa) y lanza una NUEVA con los
     * MISMOS parámetros de la ejecución anterior — incluida la misma seed, de modo que el reinicio
     * re-juega una corrida idéntica. Devuelve el job nuevo (con jobId nuevo) o {@code null} si el
     * job no existe o su escenario no es reiniciable.
     *
     * <p>El executor es single-thread: tras cancelar, el job nuevo se encola y arranca cuando el
     * worker anterior retorna por la interrupción, sin solapamiento (no hace falta esperar).
     */
    public JobState reiniciarJob(String jobId) {
        JobState viejo = getJob(jobId);
        if (viejo == null) return null;
        if (JobsRegistry.ESTADOS_ACTIVOS.contains(viejo.estado)) {
            cancelarJob(jobId);
        }
        return switch (viejo.getEscenario()) {
            case "1" -> iniciarEscenario1Async(viejo.algoritmo, viejo.seed, viejo.fechaInicio);
            case "2" -> {
                EjecucionParams p = new EjecucionParams();
                p.setMotor(viejo.algoritmo);
                p.setSeed(viejo.seed);
                p.setFechaInicio(viejo.fechaInicio);
                p.setSaMin(viejo.saMin);
                p.setTaSegundos(viejo.taSegundos);
                p.setDias(viejo.dias);
                p.setProcesamientoPrevio(viejo.procesamientoPrevio);
                yield iniciarEscenario2Async(p);
            }
            case "3" -> iniciarEscenario3Async(
                    viejo.umbralColapso != null ? viejo.umbralColapso : 0.20,
                    viejo.algoritmo, viejo.seed, viejo.fechaInicio);
            default -> null;
        };
    }

    /** Lista los jobs activos (encolado/calentando/ejecutando). */
    public List<JobState> listarJobsActivos() {
        return jobs.listarActivos();
    }

    /** Lista todos los jobs vivos en memoria (activos y terminados). */
    public List<JobState> listarTodosLosJobs() {
        return jobs.listarTodos();
    }

    /**
     * Read model del listado de jobs ({@code GET /jobs}). Antes el controller armaba el mapa a mano;
     * aquí se construye el DTO tipado byte-compatible.
     *
     * @param activos si true, solo jobs en estado activo (encolado/calentando/ejecutando).
     */
    public JobsListResponse listarJobsResponse(boolean activos) {
        List<JobState> lista = activos ? listarJobsActivos() : listarTodosLosJobs();
        List<JobsListResponse.JobResumen> items = new ArrayList<>(lista.size());
        for (JobState j : lista) {
            JobsListResponse.JobResumen item = new JobsListResponse.JobResumen();
            item.setJobId(j.getJobId());
            item.setEscenario(j.getEscenario());
            item.setAlgoritmo(j.algoritmo);
            item.setEstado(j.estado);
            item.setK(j.getK());
            item.setSeed(j.seed);
            if (j.fechaInicio != null) item.setFechaInicio(j.fechaInicio.toString());
            item.setInicio(j.inicio.toString());
            if (j.fin != null) item.setFin(j.fin.toString());
            item.setProgreso(j.getProgreso());
            item.setProgresoWarmup(j.getProgresoWarmup());
            items.add(item);
        }
        JobsListResponse body = new JobsListResponse();
        body.setJobs(items);
        body.setTotal(items.size());
        return body;
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
     * Drena la cola de órdenes de cancelación y las aplica sobre la corrida en curso: marca cada
     * vuelo-día como no disponible en el enrutador (capacidad 0) y devuelve al backlog los envíos ya
     * comprometidos en él, que {@code procesarBloque} liberará y re-enrutará en el bloque actual.
     *
     * @return cantidad de vuelo-días efectivamente cancelados en esta llamada (para acumular).
     */
    private int aplicarCancelacionesVuelo(java.util.Queue<CancelacionVueloRequest> cola, Graph graph,
                                          GreedyRepairOperator enrutador, BacklogManager backlog,
                                          List<VueloCancelado> registro,
                                          List<CancelacionVueloRequest> noAplicadas) {
        if (cola == null || cola.isEmpty() || graph == null || enrutador == null) return 0;
        // Fase 5b: índice id_vuelo→Edge para reconstruir desde BD los envíos afectados (solo se
        // construye cuando hay cancelaciones reales que aplicar, evento raro).
        Map<String, Edge> indiceVuelo = solucionBdReader.construirIndiceVuelo(graph);
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
            // dep se interpreta en UTC: el front reenvía el fechaSalida UTC de /vuelos/usados. Se
            // compara contra Edge.depMinuteOfDay (que AlgorithmMapper dejó en UTC) y el día sale del
            // mismo valor. Si el front mandara la hora local de pared, no casaría (salvo offset 0).
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
                // No falla en silencio: se registra para exponerlo en GET /jobs/{id}/estado.
                if (noAplicadas != null) noAplicadas.add(orden);
                continue;
            }

            for (Edge e : matches) {
                if (enrutador.addCancelledFlight(FlightKeyEncoder.flightKey(e.idx, epochMin))) {
                    cancelados++;
                }
            }
            int afectados = reencolarAfectadosPorCancelacion(matches, epochDay, backlog, indiceVuelo);
            if (registro != null) {
                registro.add(new VueloCancelado(origen, destino, dep, afectados));
            }
            log.info("Vuelo cancelado {}->{} salida {} ({} edge-día) — {} envíos devueltos al backlog",
                    origen, destino, dep, matches.size(), afectados);
        }
        return cancelados;
    }

    /**
     * Devuelve al backlog (como replanificables) los envíos ya comprometidos cuya ruta ACTIVA usa
     * alguno de los {@code edgesCancelados} en el día {@code epochDay}. Fase 5b: los afectados se
     * leen de BD ({@code ruta_asignada}/{@code tramo_ruta}) y se reconstruyen CON su ruta vieja, en
     * vez de iterar un acumulador en RAM. No libera capacidad aquí: {@code procesarBloque} llama
     * {@code releaseFromGlobal} + {@code clearRoute} al sacarlos del backlog (necesita la ruta vieja,
     * por eso se reconstruye). Si el envío VENCE antes de ser reprocesado, la liberación corre por el
     * hook de descarte del backlog (ver {@code crearBacklogConPurga}).
     */
    private int reencolarAfectadosPorCancelacion(List<Edge> edgesCancelados, long epochDay,
                                                 BacklogManager backlog,
                                                 Map<String, Edge> indiceVuelo) {
        if (backlog == null || edgesCancelados == null || edgesCancelados.isEmpty()) return 0;
        List<String> idsVuelo = new ArrayList<>(edgesCancelados.size());
        for (Edge e : edgesCancelados) idsVuelo.add(PersistenciaSolucionService.normalizarIdVuelo(e.id));

        List<LuggageBatch> afectados = solucionBdReader.afectadosPorVuelo(
                idsVuelo, java.time.LocalDate.ofEpochDay(epochDay), indiceVuelo);
        for (LuggageBatch b : afectados) backlog.addReplanificable(b);
        return afectados.size();
    }

    /**
     * Catálogo de escenarios disponibles para el front. Devuelve los valores por defecto
     * (Sa, Ta, K, umbrales) que toma el backend desde {@link PlanificadorProperties.Scenario},
     * junto con una descripción human-readable y la lista de motores soportados.
     *
     * <p>Pensado para que el front no tenga que hardcodear los defaults ni los textos de los
     * escenarios. Antes se construía a mano en el controller; se movió aquí en la Tanda 1D para
     * que el controller solo delegue.
     */
    public Map<String, Object> getCatalogoEscenarios() {
        PlanificadorProperties.Scenario sc = props.getScenario();

        Map<String, Object> esc1 = new HashMap<>();
        esc1.put("id", 1);
        esc1.put("nombre", "Día a día (tiempo real)");
        esc1.put("descripcion",
                "Planificación viva: cada corrida cubre un único bloque Sa. " +
                "El wall-clock por bloque es Sa real, sin aceleración.");
        esc1.put("kDefault", sc.getKDefault1());
        esc1.put("kFijo", true);   // K inmutable por regla de negocio (E1=1)
        esc1.put("simulaTiempoReal", sc.isSimularTiempoReal1());
        esc1.put("endpoints", Map.of(
                "iniciar", "POST /api/planificador/escenario1/iniciar"
        ));

        Map<String, Object> esc2 = new HashMap<>();
        esc2.put("id", 2);
        esc2.put("nombre", "Período (3/5/7 días)");
        esc2.put("descripcion",
                "Replays/simulaciones de un período cerrado. Entre bloques duerme " +
                "(Sa - Ta) cuando simularTiempoReal2=true, para imitar el ritmo real.");
        esc2.put("kDefault", sc.getKDefault2());
        esc2.put("kFijo", true);   // K inmutable por regla de negocio (E2=144)
        esc2.put("simulaTiempoReal", sc.isSimularTiempoReal2());
        esc2.put("endpoints", Map.of(
                "iniciar", "POST /api/planificador/escenario2/iniciar"
        ));

        Map<String, Object> esc3 = new HashMap<>();
        esc3.put("id", 3);
        esc3.put("nombre", "Hasta colapso");
        esc3.put("descripcion",
                "Estrés / capacity planning. Avanza lo más rápido posible (a menos " +
                "que simularTiempoReal3=true) hasta que se dispara la condición de colapso.");
        esc3.put("kDefault", sc.getKDefault3());
        esc3.put("kFijo", true);   // K inmutable por regla de negocio (E3=144)
        esc3.put("simulaTiempoReal", sc.isSimularTiempoReal3());
        esc3.put("umbralColapso", sc.getUmbralColapso());
        esc3.put("umbralColapsoBacklog", sc.getUmbralColapsoBacklog());
        esc3.put("endpoints", Map.of(
                "iniciar", "POST /api/planificador/escenario3/iniciar"
        ));

        Map<String, Object> body = new HashMap<>();
        body.put("saMinutos", sc.getSaMinutos());
        body.put("taSegundos", sc.getTaSegundos());
        body.put("motoresSoportados", List.of(MOTOR_ALNS, MOTOR_ACO));
        body.put("escenarios", List.of(esc1, esc2, esc3));
        return body;
    }

    /**
     * Serie de ocupación de almacenes por slot ({@code GET /jobs/{id}/almacenes/serie?desde=N}).
     * Antes el controller armaba el mapa a mano; aquí se construye el DTO tipado byte-compatible.
     * Devuelve {@code null} si el job no existe (el controller responde 404).
     */
    public SerieAlmacenesResponse getSerieAlmacenes(String jobId, int desde) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        int desdeNorm = Math.max(0, desde);
        List<List<OcupacionAlmacenSlot>> series = job.seriesDesde(desdeNorm);
        List<SerieAlmacenesResponse.SerieItem> filas = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            SerieAlmacenesResponse.SerieItem item = new SerieAlmacenesResponse.SerieItem();
            item.setBloqueIdx(desdeNorm + i);
            item.setSlots(series.get(i));
            filas.add(item);
        }

        SerieAlmacenesResponse body = new SerieAlmacenesResponse();
        body.setJobId(job.getJobId());
        body.setDesde(desdeNorm);
        body.setTotal(job.seriesPublicadas());
        body.setTerminado(!"encolado".equals(job.estado)
                && !"calentando".equals(job.estado)
                && !"ejecutando".equals(job.estado));
        body.setSeries(filas);
        return body;
    }

    /**
     * Construye el cuerpo de {@code GET /jobs/{id}/estado-inicial} a partir de un job cuyo
     * {@code estadoInicial} ya está calculado (no {@code null}). Las decisiones de estado HTTP
     * (404 si el job no existe, 204 si el snapshot aún no está) se quedan en el controller; aquí solo
     * se serializa el snapshot. Byte-compatible con el mapa anterior.
     */
    public EstadoInicialResponse buildEstadoInicialResponse(JobState job) {
        EstadoInicialResponse body = new EstadoInicialResponse();
        body.setJobId(job.getJobId());
        if (job.fechaInicio != null) body.setFechaInicio(job.fechaInicio.toString());
        List<AsignacionMaleta> snapshot = job.estadoInicial;
        body.setTotal(snapshot.size());
        body.setAsignaciones(snapshot);
        return body;
    }

    /**
     * Estado y progreso de un job ({@code GET /jobs/{id}/estado}). Antes el controller armaba el
     * mapa a mano; aquí se construye el DTO tipado byte-compatible (incluye warm-up, posición en
     * cola, alerta de colapso inminente y cancelaciones de vuelo ya aplicadas).
     */
    public EstadoJobResponse getEstadoJob(String jobId) {
        JobState job = getJob(jobId);
        if (job == null) return null;

        EstadoJobResponse body = new EstadoJobResponse();
        body.setJobId(job.getJobId());
        body.setEscenario(job.getEscenario());
        body.setAlgoritmo(job.algoritmo);
        body.setSeed(job.seed);
        if (job.fechaInicio != null) body.setFechaInicio(job.fechaInicio.toString());
        body.setK(job.getK());
        body.setEstado(job.estado);
        body.setBloqueActual(job.bloqueActual);
        body.setTotalBloques(job.totalBloques);
        body.setProgreso(job.getProgreso());
        // Warm-up: el front consulta estos campos mientras estado="calentando".
        body.setBloqueWarmup(job.bloqueWarmup);
        body.setTotalBloquesWarmup(job.totalBloquesWarmup);
        body.setProgresoWarmup(job.getProgresoWarmup());
        // Cola: posicionEnCola indica el turno (1-based) si estado="encolado"; 0 si ya corre o terminó.
        body.setPosicionEnCola(posicionEnCola(jobId));
        // Cancelación: distingue cancelación voluntaria de fallo real sin parsear `error`.
        body.setCanceladoPorUsuario(job.canceladoPorUsuario);
        body.setTaPromedioMs(job.taPromedioMs);
        body.setInicio(job.inicio.toString());
        if (job.fin != null) body.setFin(job.fin.toString());
        if (job.error != null) body.setError(job.error);
        // Alerta de colapso INMINENTE (pre-colapso) del último bloque, si existe.
        if (job.alertaColapso != null) body.setAlertaColapso(job.alertaColapso);
        // Cancelaciones de vuelo YA aplicadas (con envíos afectados), en orden de aplicación.
        body.setVuelosCancelados(job.getVuelosCancelados());
        // Cancelaciones que no casaron ningún vuelo-día (p. ej. eje equivocado): no fallar en silencio.
        body.setCancelacionesNoAplicadas(job.getCancelacionesNoAplicadas());
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
        // Cancelaciones que no casaron ningún vuelo-día: se exponen en /estado (no fallar en silencio).
        List<CancelacionVueloRequest> cancelacionesNoAplicadas =
                job != null ? job.getCancelacionesNoAplicadas() : new ArrayList<>();

        List<BloqueSimulacion> bloques = new ArrayList<>(totalBloques);
        Map<String, int[]> odStats = new HashMap<>();
        int totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
                totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        TaStats taStats = new TaStats();
        boolean simularTiempoReal = props.getScenario().isSimularTiempoReal2();
        long saMs = saMin * 60_000L;
        // G2: purga activa para acotar el backlog (los vencidos dejan de reintentarse).
        BacklogManager backlog = crearBacklogConPurga(enrutador);
        AcumuladorAuditoria auditAcc = new AcumuladorAuditoria(false);

        // ── Fase warm-up ────────────────────────────────────────────────────
        // Se ejecuta el plan [primera-ventana, fechaInicio) compartiendo
        // graph/enrutador/backlog/odStats con la fase visible. Auditoría y
        // métricas del warm-up van a un acumulador descartable; del acumulador
        // se deriva el estado inicial para el front (aviones aún en el aire).
        AcumuladorAuditoria auditWarmup = ejecutarWarmup(warmupPlan, job, graph, enrutador,
                solucionDummy, odStats, backlog, motorRes, seed, taFijoMs, fechaInicio);
        if (job != null) job.estadoInicial = construirEstadoInicial(auditWarmup.completos());

        // Fase T (N3) — pre-calienta la caché de esqueletos con la demanda de toda la ventana antes
        // del bucle de bloques: mueve el costo del Dijkstra FUERA del presupuesto Ta (sube throughput
        // en arranque limpio / caché fría). Ta-safe y no cambia rutas (la materialización revalida
        // capacidad por bloque). Reversible con planificador.scenario.prewarm-skeletons=false.
        if (props.getScenario().isPrewarmSkeletons() && !plan.isEmpty()) {
            long t0Prewarm = System.currentTimeMillis();
            List<Envio> demandaVentana = dataLoader.getMaletasEnRango(
                    plan.get(0).scStart, plan.get(plan.size() - 1).scEnd);
            int clavesCalentadas = enrutador.precalentarEsqueletos(
                    mapper.mapToBatches(demandaVentana), PREWARM_ROUTE_CANDIDATES);
            log.info("Pre-warm esqueletos (N3): {} claves desde {} envíos en {} ms",
                    clavesCalentadas, demandaVentana.size(), System.currentTimeMillis() - t0Prewarm);
        }

        // Fase 5a: toma la persistencia (una corrida a la vez) y limpia las tablas de solución.
        persistencia.iniciarCorrida(job != null ? job.getJobId() : null);

        boolean colapsoAlmacenDetectado = false;   // E2 se detiene ante colapso de almacén.
        int bloqueColapsoAlmacen = -1;
        String nivelAlertaPrevio = AlertaColapso.VERDE;
        for (TemporalContext ctx : plan) {
            bloqueActual++;
            // Cancelaciones de vuelo ordenadas por el usuario en vivo: se aplican antes de procesar.
            totalVuelosCancelados += aplicarCancelacionesVuelo(
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, vuelosCancelados, cancelacionesNoAplicadas);
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motorRes, rngBloque, taFijoMs);
            bloques.add(rv.bloque);
            persistencia.persistirBloque(job != null ? job.getJobId() : null, rv.finalBatches());
            taStats.acumular(ctx.taMs);

            TotalesUnicos totales = auditAcc.totalesUnicos();
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
                job.publicarSerieAlmacenes(rv.serieAlmacenes());
                job.metricasSnapshot = metricasSnapshotDe(totales, taStats.promedio());
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
        publicarAuditoria(job, auditAcc, vuelosCancelados, graph);
        return res;
    }

    public BloqueSimulacion getBloque(int index) {
        if (bloquesCacheados == null || index < 0 || index >= bloquesCacheados.size()) return null;
        return bloquesCacheados.get(index);
    }


    /**
     * Ejecuta el escenario 1 como un job continuo: K=1 día-a-día, sleep
     * {@code Sa - Ta} entre bloques cuando {@code simularTiempoReal1=true}.
     * Publica bloques incrementalmente vía {@code JobState.publicarBloque} y
     * arma un {@link SimulacionResponse} agregado al final.
     */
    public SimulacionResponse ejecutarEscenario1(JobState job, String motor, long seed) {
        return ejecutarEscenario1(job, motor, seed, null);
    }

    /**
     * Variante con {@code fechaInicio}: si es posterior a la primera ventana del dataset, se
     * pre-calcula el período previo como warm-up (Ta como cota dura, SIN el sleep de Sa) y la
     * fase visible arranca en fechaInicio respetando Sa. El estado inicial (aviones aún en el
     * aire al llegar a fechaInicio) queda en {@code job.estadoInicial}.
     */
    public SimulacionResponse ejecutarEscenario1(JobState job, String motor, long seed,
                                                 LocalDateTime fechaInicio) {
        String motorRes = resolverMotor(motor);
        int k = props.getScenario().getKDefault1();
        int saMin = props.getScenario().getSaMinutos();
        long taFijoMs = props.getScenario().getTaSegundos() * 1000L;
        int scMin = Math.max(saMin, k * saMin);
        log.info("Escenario 1 — motor={} seed={} (K={}, Sa={}min, Sc={}min, fechaInicio={}, async={}) ...",
                motorRes, seed, k, saMin, scMin, fechaInicio, job != null);
        long inicio = System.currentTimeMillis();

        List<TemporalContext> plan = construirPlanBloques(k, fechaInicio);
        List<TemporalContext> warmupPlan = fechaInicio != null
                ? construirPlanWarmup(k, fechaInicio, null)
                : Collections.emptyList();
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
        // Cancelaciones que no casaron ningún vuelo-día: se exponen en /estado (no fallar en silencio).
        List<CancelacionVueloRequest> cancelacionesNoAplicadas =
                job != null ? job.getCancelacionesNoAplicadas() : new ArrayList<>();

        List<BloqueSimulacion> bloques = new ArrayList<>(plan.size());
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
        AcumuladorAuditoria auditAcc = new AcumuladorAuditoria(false);
        int intervaloReporte = Math.max(1, totalBloques / 10);
        // Fase 5a: toma la persistencia (una corrida a la vez) y limpia las tablas de solución.
        persistencia.iniciarCorrida(job != null ? job.getJobId() : null);

        boolean colapsoAlmacenDetectado = false;   // E1 se detiene ante colapso de almacén.
        int bloqueColapsoAlmacen = -1;
        String nivelAlertaPrevio = AlertaColapso.VERDE;

        // Warm-up (fechaInicio posterior al inicio de datos): pre-calcula el período previo
        // respetando Ta pero SIN el sleep de Sa; deja el estado inicial para el front.
        AcumuladorAuditoria auditWarmup = ejecutarWarmup(warmupPlan, job, graph, enrutador,
                solucionDummy, odStats, backlog, motorRes, seed, taFijoMs, fechaInicio);
        if (job != null) job.estadoInicial = construirEstadoInicial(auditWarmup.completos());

        for (TemporalContext ctx : plan) {
            bloqueActual++;
            // Cancelaciones de vuelo ordenadas por el usuario en vivo: se aplican antes de procesar.
            totalVuelosCancelados += aplicarCancelacionesVuelo(
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, vuelosCancelados, cancelacionesNoAplicadas);
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motorRes, rngBloque, taFijoMs);

            rv.bloque.setTiempoProcesamientoMs(ctx.taMs);

            bloques.add(rv.bloque);
            persistencia.persistirBloque(job != null ? job.getJobId() : null, rv.finalBatches());
            taStats.acumular(ctx.taMs);

            TotalesUnicos totales = auditAcc.totalesUnicos();
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
                job.publicarSerieAlmacenes(rv.serieAlmacenes());
                job.metricasSnapshot = metricasSnapshotDe(totales, taStats.promedio());
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
        publicarAuditoria(job, auditAcc, vuelosCancelados, graph);
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
        return ejecutarHastaColapso(k, umbralColapso, job, motor, seed, null);
    }

    /**
     * Variante con {@code fechaInicio}: pre-calcula el período previo como warm-up (Ta como
     * cota dura, SIN el sleep de Sa) y la fase visible — donde se vigila el colapso — arranca
     * en fechaInicio respetando Sa. Estado inicial en {@code job.estadoInicial}.
     */
    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso,
                                                   JobState job, String motor, long seed,
                                                   LocalDateTime fechaInicio) {
        String motorRes = resolverMotor(motor);
        Random rngSim = new Random(seed);
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        int saMin = props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);
        log.info("Escenario 3 — colapso motor={} seed={} (K={}, Sa={}min, Sc={}min, umbral={}%, fechaInicio={}, async={}) ...",
                motorRes, seed, k, saMin, scMin,
                String.format("%.1f", umbralColapso * 100),
                fechaInicio, job != null);
        long inicio = System.currentTimeMillis();

        List<TemporalContext> plan = construirPlanBloques(k, fechaInicio);
        List<TemporalContext> warmupPlan = fechaInicio != null
                ? construirPlanWarmup(k, fechaInicio, null)
                : Collections.emptyList();
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
        // Cancelaciones que no casaron ningún vuelo-día: se exponen en /estado (no fallar en silencio).
        List<CancelacionVueloRequest> cancelacionesNoAplicadas =
                job != null ? job.getCancelacionesNoAplicadas() : new ArrayList<>();

        List<BloqueSimulacion> bloques = new ArrayList<>();
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
        AcumuladorAuditoria auditAcc = new AcumuladorAuditoria(false);

        // Warm-up (fechaInicio posterior al inicio de datos): pre-calcula el período previo
        // respetando Ta pero SIN el sleep de Sa. El colapso solo se vigila en la fase visible.
        AcumuladorAuditoria auditWarmup = ejecutarWarmup(warmupPlan, job, graph, enrutador,
                solucionDummy, odStats, backlog, motorRes, seed,
                props.getScenario().getTaSegundos() * 1000L, fechaInicio);
        if (job != null) job.estadoInicial = construirEstadoInicial(auditWarmup.completos());

        // Fase 5a: toma la persistencia (una corrida a la vez) y limpia las tablas de solución.
        persistencia.iniciarCorrida(job != null ? job.getJobId() : null);

        for (TemporalContext ctx : plan) {
            bloqueActual++;
            // Cancelaciones de vuelo ordenadas por el usuario en vivo: se aplican antes de procesar.
            aplicarCancelacionesVuelo(
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, vuelosCancelados, cancelacionesNoAplicadas);
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motorRes, rngBloque);

            rv.bloque.setTiempoProcesamientoMs(ctx.taMs);

            bloques.add(rv.bloque);
            persistencia.persistirBloque(job != null ? job.getJobId() : null, rv.finalBatches());
            taStats.acumular(ctx.taMs);

            TotalesUnicos totales = auditAcc.totalesUnicos();
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
                job.publicarSerieAlmacenes(rv.serieAlmacenes());
                job.metricasSnapshot = metricasSnapshotDe(totales, taStats.promedio());
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
        publicarAuditoria(job, auditAcc, vuelosCancelados, graph);
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
    private void publicarAuditoria(JobState job, AcumuladorAuditoria auditAcc,
                                   List<VueloCancelado> vuelosCancelados, Graph graph) {
        String jobId = job != null ? job.getJobId() : null;
        // Fase 5b: ¿ESTE job tiene su solución en BD? (consultar ANTES de soltar el lock).
        final boolean persistio = persistencia.persiste(jobId);
        if (auditoria == null || job == null) {
            persistencia.finalizarCorrida(jobId);
            return;
        }
        try {
            // Sin-ruta: no llegaron a BD (Fase 5a solo persiste enrutados) ⇒ se auditan desde RAM
            // (son ligeros, sin Edges).
            List<LuggageBatch> sinRuta = auditAcc != null
                    ? new ArrayList<>(auditAcc.sinRuta()) : new ArrayList<>();
            boolean hayCancelados = vuelosCancelados != null && !vuelosCancelados.isEmpty();
            // Generar el ZIP si hay enrutados en BD, sin-ruta en RAM o vuelos cancelados.
            if (!persistio && sinRuta.isEmpty() && !hayCancelados) return;

            // Si el job fue cancelado vía Future.cancel(true), el thread llega aquí con el flag
            // interrupted activo. La escritura del ZIP usa canales NIO interrumpibles que lanzarían
            // ClosedByInterruptException; limpiamos el flag (es la última operación del job).
            Thread.interrupted();

            // Enrutados: desde BD si este job persistió. Si no (caso raro de multi-job sin lock), la
            // Fase 5b ya no los retiene en RAM ⇒ ZIP degradado (solo sin-ruta + cancelados).
            Map<String, Edge> indiceVuelo = solucionBdReader.construirIndiceVuelo(graph);
            java.util.function.Consumer<java.util.function.Consumer<LuggageBatch>> fuenteEnrutados =
                    persistio ? sink -> solucionBdReader.forEachEnrutado(indiceVuelo, sink)
                              : sink -> { };

            log.info("Generando auditoria ZIP desde {} (job {})",
                    persistio ? "BD" : "memoria(degradado, solo sin-ruta)", jobId);
            Path path = Files.createTempFile("planificador-auditoria-" + jobId + "-", ".zip");
            path.toFile().deleteOnExit();
            int filas = auditoria.escribirZipStreaming(path, AuditoriaService.FILAS_POR_ARCHIVO, jobId,
                    fuenteEnrutados, sinRuta, vuelosCancelados);
            job.auditoriaZipPath = path;
            job.auditoriaCsvPath = null;
            job.auditoriaCsv = null;
            job.auditoriaFilas = filas;
            log.info("Auditoria ZIP generada: {} filas (job {}) en {}", filas, jobId, path);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar auditoria ZIP", e);
        } finally {
            // Libera el lock SOLO tras leer el ZIP desde BD: si lo soltáramos antes, otro job podría
            // TRUNCAR las tablas de solución mientras las leemos.
            persistencia.finalizarCorrida(jobId);
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
                                            AcumuladorAuditoria auditAcc) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, MOTOR_ALNS, null);
    }

    private ResultadoVentana procesarBloque(TemporalContext ctx,
                                            Graph graph,
                                            GreedyRepairOperator enrutador,
                                            AlnsSolution solucionDummy,
                                            Map<String, int[]> odStats,
                                            BacklogManager backlog,
                                            AcumuladorAuditoria auditAcc,
                                            String motor) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motor, null, 0L);
    }

    private ResultadoVentana procesarBloque(TemporalContext ctx,
                                            Graph graph,
                                            GreedyRepairOperator enrutador,
                                            AlnsSolution solucionDummy,
                                            Map<String, int[]> odStats,
                                            BacklogManager backlog,
                                            AcumuladorAuditoria auditAcc,
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
                                            AcumuladorAuditoria auditAcc,
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
                                            AcumuladorAuditoria auditAcc,
                                            String motor,
                                            Random rngSim,
                                            long taFijoMsOverride,
                                            boolean fastForward) {
        ctx.marcarInicio();

        // 1. Eje de datos: consumir [scStart, scEnd) → todo lo registrado en ese rango.
        List<Envio> maletasVentana = dataLoader.getMaletasEnRango(ctx.scStart, ctx.scEnd);
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

        // 3. Motor: ALNS (Greedy + Dijkstra + ALNS) o ACO (AcoBlockEngine por bloque).
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

        List<AsignacionMaleta> asignaciones = buildAsignaciones(finalBatches);

        int enrutadas = (int) asignaciones.stream().filter(AsignacionMaleta::isEnrutada).count();
        int cumpleSLA = (int) asignaciones.stream().filter(a -> a.isEnrutada() && a.isCumpleSLA()).count();
        int tardadas = enrutadas - cumpleSLA;
        int sinRuta = finalBatches.size() - enrutadas;
        long maletas = maletasVentana.stream()
                .mapToLong(m -> m.getCantidad() != null ? m.getCantidad() : 0L)
                .sum();

        for (AsignacionMaleta a : asignaciones) {
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
            for (LuggageBatch b : finalBatches) auditAcc.registrar(b);
        }

        // Reportar Ta como variable fija del modelo (taMs = ta-segundos * 1000).
        // Si Ta no está configurado, queda el legacy (taMs = tiempo medido).
        ctx.marcarFin(taFijoMs);

        BloqueSimulacion bloque = new BloqueSimulacion();
        // El cursor de ventanas avanza en UTC (DataLoader filtra la demanda por registroUtc), así
        // que scStart/scEnd ya son los LÍMITES UTC de la ventana. horaInicio/horaFin y su alias
        // horaInicioUtc/horaFinUtc se derivan directamente de ellos: bloques UTC contiguos
        // (horaFinUtc[N] == horaInicioUtc[N+1]), estables aun con reintentos del backlog cuyos
        // registroUtc son anteriores a la ventana.
        bloque.setHoraInicio(ctx.scStart.toString());
        bloque.setHoraFin(ctx.scEnd.toString());
        bloque.setHoraInicioUtc(ctx.scStart.toString());
        bloque.setHoraFinUtc(ctx.scEnd.toString());
        bloque.setMaletasProcesadas(finalBatches.size());
        bloque.setMaletasEnrutadas(enrutadas);
        bloque.setAsignaciones(asignaciones);
        // El bloque ya fue commiteado a la ocupación global ⇒ la telemetría reporta el ACUMULADO
        // (global incluye este bloque) de cada recurso tocado, no el delta del bloque.
        bloque.setCargasVuelos(buildCargasVuelos(telemetryFlight, graph, enrutador));
        bloque.setOcupacionAlmacenes(buildOcupacionAlmacenes(telemetryAirport, graph, enrutador));
        // Alerta de almacén cerca de colapso, ESPECÍFICA de este bloque: viaja embebida para que el
        // front la muestre sincronizada con la animación, aunque el backend ya vaya en bloques futuros.
        bloque.setAlertaAlmacen(construirAlertaAlmacen(bloque.getOcupacionAlmacenes(), ctx.bloqueIdx));
        bloque.setBloqueIdx(ctx.bloqueIdx);
        bloque.setTaMs(ctx.taMs);
        bloque.setScMinutos(ctx.scMinutos);
        if (auditAcc != null) auditAcc.llenarAcumuladosFisicos(bloque);

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

        // Serie por slots para la animación en vivo (no se calcula en warm-up: no se publica).
        List<OcupacionAlmacenSlot> serieAlmacenes = fastForward
                ? List.of()
                : buildSerieAlmacenes(telemetryAirport, graph, enrutador);

        return new ResultadoVentana(bloque, finalBatches.size(), enrutadas, sinRuta, cumpleSLA, tardadas, maletas,
                colapsoAlmacen, detalleColapso, alerta, serieAlmacenes, finalBatches);
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
    List<AsignacionMaleta> buildAsignaciones(List<LuggageBatch> batches) {
        return batches.stream().map(b -> {
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            AsignacionMaleta asig = new AsignacionMaleta();
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

            List<TramoRuta> tramos = Collections.emptyList();
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
                    TramoRuta tr = new TramoRuta();
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

    // Package-private para tests (telemetría por bloque vs ocupación acumulada).
    // El mapa del bloque selecciona QUÉ vuelos-día reportar (los tocados en este bloque); la carga
    // reportada es la ACUMULADA global del enrutador, que tras commitBlock ya incluye el bloque.
    // Sin enrutador (legacy/tests) se reporta el delta del bloque, como antes del fix.
    List<CargaVuelo> buildCargasVuelos(Map<Long, Integer> blockFlight, Graph graph,
                                                          GreedyRepairOperator enrutador) {
        if (blockFlight == null || blockFlight.isEmpty() || graph == null) return List.of();

        Map<Integer, Edge> edgesByIdx = new HashMap<>();
        for (Edge edge : graph.edges) edgesByIdx.put(edge.idx, edge);

        List<CargaVuelo> out = new ArrayList<>();
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

            CargaVuelo dto = new CargaVuelo();
            dto.setVueloId(edge.id);
            dto.setOrigen(edge.from != null ? edge.from.code : "");
            dto.setDestino(edge.to != null ? edge.to.code : "");
            dto.setFechaSalida(salida.toString());
            dto.setFechaLlegada(llegada.toString());
            dto.setCapacidadMaxima(edge.capacity);
            dto.setCargaAsignada(carga);
            SimulacionFormat.completarCargaVuelo(dto);
            out.add(dto);
        }
        out.sort(Comparator.comparing(CargaVuelo::getFechaSalida)
                .thenComparing(CargaVuelo::getVueloId));
        return out;
    }

    // Package-private para tests (telemetría por bloque vs ocupación acumulada).
    // El mapa del bloque selecciona QUÉ slots reportar (los tocados en este bloque); la ocupación
    // reportada es la ACUMULADA global del enrutador (tras commitBlock incluye el bloque, más la
    // espera en origen del backlog). Sin enrutador (legacy/tests) se reporta el delta del bloque.
    List<OcupacionAlmacen> buildOcupacionAlmacenes(Map<Long, Integer> blockAirport,
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

        List<OcupacionAlmacen> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : picoPorAeroDia.entrySet()) {
            Node node = nodesByIdx.get(resourceIdx(entry.getKey()));
            if (node == null) continue;

            OcupacionAlmacen dto = new OcupacionAlmacen();
            dto.setAeropuerto(node.code);
            dto.setFecha(LocalDate.ofEpochDay(epochDay(entry.getKey())).toString());
            dto.setCapacidadMaxima(node.capacity);
            dto.setOcupacionAsignada(entry.getValue());   // pico concurrente del día
            SimulacionFormat.completarOcupacionAlmacen(dto);
            out.add(dto);
        }
        out.sort(Comparator.comparing(OcupacionAlmacen::getFecha)
                .thenComparing(OcupacionAlmacen::getAeropuerto));
        return out;
    }

    // Package-private para tests (consistencia serie ↔ modelo interno).
    // Serie temporal por SLOT de 60 min para la animación en vivo: por cada slot tocado por el
    // bloque, la ocupación ACUMULADA vigente (global del enrutador, que tras commitBlock incluye
    // el bloque y la espera en origen del backlog). Es la MISMA granularidad que valida el
    // Dijkstra — el DTO no agrega ni descarta nada, solo decodifica la clave de slot a
    // (aeropuerto, hora UTC).
    List<OcupacionAlmacenSlot> buildSerieAlmacenes(Map<Long, Integer> blockAirport,
                                                                      Graph graph,
                                                                      GreedyRepairOperator enrutador) {
        if (blockAirport == null || blockAirport.isEmpty() || graph == null) return List.of();

        Map<Integer, Node> nodesByIdx = new HashMap<>();
        for (Node node : graph.nodes.values()) nodesByIdx.put(node.idx, node);

        List<OcupacionAlmacenSlot> out = new ArrayList<>(blockAirport.size());
        for (Map.Entry<Long, Integer> entry : blockAirport.entrySet()) {
            int ocupacion = enrutador != null
                    ? enrutador.ocupacionGlobalAlmacen(entry.getKey())
                    : entry.getValue();
            if (ocupacion <= 0) continue;
            Node node = nodesByIdx.get(resourceIdx(entry.getKey()));
            if (node == null) continue;

            long slot = entry.getKey() & FlightKeyEncoder.DAY_MASK;   // índice de slot (epochMin/60)
            long epochMin = slot * GreedyRepairOperator.STORAGE_SLOT_MIN;

            OcupacionAlmacenSlot dto = new OcupacionAlmacenSlot();
            dto.setAeropuerto(node.code);
            dto.setHora(epochMinToIso(epochMin));
            dto.setCapacidadMaxima(node.capacity);
            dto.setOcupacion(ocupacion);
            double porcentaje = SimulacionFormat.porcentaje(ocupacion, node.capacity);
            dto.setPorcentajeOcupacion(porcentaje);
            dto.setSemaforo(SimulacionFormat.semaforoPorPorcentaje(porcentaje));
            out.add(dto);
        }
        out.sort(Comparator.comparing(OcupacionAlmacenSlot::getHora)
                .thenComparing(OcupacionAlmacenSlot::getAeropuerto));
        return out;
    }

    /**
     * Construye la alerta de almacén del bloque: el almacén con mayor % de ocupación entre los del
     * bloque, reusando su semáforo ya calculado ("VERDE"/"AMBAR"/"ROJO", umbrales 0.70/0.90). Si el
     * bloque no tocó ningún almacén, devuelve nivel "VERDE" sin almacén crítico. La alerta viaja
     * embebida en el bloque para sincronizarse con la animación del front.
     */
    // package-private para test unitario (AlertaAlmacenTest, mismo paquete).
    static AlertaAlmacen construirAlertaAlmacen(
            List<OcupacionAlmacen> ocupaciones, int bloqueIdx) {
        AlertaAlmacen alerta = new AlertaAlmacen();
        alerta.setBloqueIdx(bloqueIdx);

        OcupacionAlmacen peor = null;
        if (ocupaciones != null) {
            for (OcupacionAlmacen o : ocupaciones) {
                if (peor == null || o.getPorcentajeOcupacion() > peor.getPorcentajeOcupacion()) {
                    peor = o;
                }
            }
        }
        if (peor == null) {
            alerta.setNivel("VERDE");
            return alerta;
        }
        alerta.setNivel(peor.getSemaforo());
        alerta.setAlmacenCritico(peor.getAeropuerto());
        alerta.setCapacidadMaxima(peor.getCapacidadMaxima());
        alerta.setOcupacion(peor.getOcupacionAsignada());
        alerta.setPorcentajeOcupacion(peor.getPorcentajeOcupacion());
        return alerta;
    }

    private static int resourceIdx(long key) {
        return (int) (key >> FlightKeyEncoder.DAY_BITS);
    }

    private static long epochDay(long key) {
        return key & FlightKeyEncoder.DAY_MASK;
    }

    // =========================================================
    // Helpers de respuesta
    // =========================================================
    private SimulacionResponse construirRespuestaFront(int enrutadas, long tiempoMs,
                                                       List<Vuelo> vuelosReales,
                                                       int totalBloques,
                                                       LocalDate simulationDate) {
        SimulacionResponse res = new SimulacionResponse();
        Metricas m = new Metricas();
        m.setEnrutadas(enrutadas);
        m.setTiempoEjecucionMs(tiempoMs);
        res.setMetricas(m);
        res.setTotalBloques(totalBloques);

        long dayShift = simulationDate != null
                ? ChronoUnit.DAYS.between(FlightParser.FLIGHT_BASE_DATE, simulationDate) : 0L;

        List<VueloBackend> vuelosFront = new ArrayList<>();
        Map<String, AeropuertoDTO> infoAero = new HashMap<>();
        for (Vuelo v : vuelosReales) {
            VueloBackend vb = new VueloBackend();
            vb.setId(SimulacionFormat.vueloFrontId(v));
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

    private static void llenarMetricas(Metricas m,
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
    private static void llenarMetricasTa(Metricas m, TaStats stats, long saMs) {
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
    /**
     * Ejecuta el plan de warm-up (pre-cálculo hasta {@code fechaInicio}) compartiendo
     * graph/enrutador/backlog/odStats con la fase visible. Cada bloque corre con
     * {@code fastForward=true}: el presupuesto Ta sigue siendo cota DURA del motor, pero se
     * salta el sleep de Sa — el warm-up avanza a velocidad de cómputo. No publica bloques;
     * el job queda en estado "calentando" y al terminar pasa a "ejecutando".
     *
     * @return el acumulador de auditoría del warm-up (descartable para métricas; de él se
     *         deriva el estado inicial del front con {@link #construirEstadoInicial}).
     */
    private AcumuladorAuditoria ejecutarWarmup(List<TemporalContext> warmupPlan, JobState job,
                                                     Graph graph, GreedyRepairOperator enrutador,
                                                     AlnsSolution solucionDummy, Map<String, int[]> odStats,
                                                     BacklogManager backlog, String motorRes, long seed,
                                                     long taFijoMs, LocalDateTime fechaInicio) {
        AcumuladorAuditoria auditWarmup = new AcumuladorAuditoria(true);
        if (warmupPlan == null || warmupPlan.isEmpty()) return auditWarmup;

        if (job != null) {
            job.estado = "calentando";
            job.totalBloquesWarmup = warmupPlan.size();
            job.bloqueWarmup = 0;
        }
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
        return auditWarmup;
    }

    /**
     * Deriva del acumulador del warm-up el ESTADO INICIAL para el front: las asignaciones cuyos
     * envíos siguen ACTIVOS al terminar el warm-up — su último arribo (UTC) es posterior al
     * reloj UTC de la simulación (max readyTime del warm-up, mismo criterio que
     * {@code llenarAcumuladosFisicos}). Incluye envíos en vuelo, en escala o con tramos aún por
     * salir; excluye los ya entregados y los sinRuta (estos siguen vivos vía backlog y
     * aparecerán en los bloques visibles). Package-private para tests.
     */
    List<AsignacionMaleta> construirEstadoInicial(Collection<LuggageBatch> batchesWarmup) {
        if (batchesWarmup == null || batchesWarmup.isEmpty()) return List.of();

        long relojMin = Long.MIN_VALUE;
        for (LuggageBatch b : batchesWarmup) {
            long readyMin = toEpochMin(b.getReadyTime());
            if (readyMin > relojMin) relojMin = readyMin;
        }

        List<LuggageBatch> activos = new ArrayList<>();
        for (LuggageBatch b : batchesWarmup) {
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (enrutada && ultimoArriboMin(b) > relojMin) activos.add(b);
        }
        return buildAsignaciones(activos);
    }

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

    /**
     * Fase 5b — Resumen ligero por envío único: lo justo para las métricas por bloque, SIN retener
     * la ruta de {@link Edge} (el grueso del peso). Se captura como snapshot al registrar el batch.
     */
    private record ResumenEnvio(long quantity, boolean enrutada, boolean cumpleSLA,
                                long readyMin, long ultimoArriboMin) {
        static ResumenEnvio de(LuggageBatch b) {
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            return new ResumenEnvio(b.getQuantity(), enrutada, b.isCumpleSLA(),
                    PlanificadorService.toEpochMin(b.getReadyTime()),
                    PlanificadorService.ultimoArriboMin(b));
        }
    }

    /**
     * Fase 5b — Acumulador de auditoría por corrida. En vez de retener cada {@link LuggageBatch}
     * completo (con su ruta, decenas de GB en el dataset completo) guarda:
     * <ul>
     *   <li>un {@link ResumenEnvio} ligero por envío único → todas las métricas por bloque;</li>
     *   <li>los batches SIN ruta (ya ligeros) para el ZIP — los enrutados se leen de BD (Fase 5a);</li>
     *   <li>en modo warm-up, los batches COMPLETOS (que {@code construirEstadoInicial} necesita para
     *       pintar los aviones en el aire); el período de warm-up es acotado.</li>
     * </ul>
     * Dedup por {@code batchAuditKey} con semántica "último estado procesado gana".
     */
    static final class AcumuladorAuditoria {
        private final Map<String, ResumenEnvio> resumen = new LinkedHashMap<>();
        private final Map<String, LuggageBatch> sinRuta = new LinkedHashMap<>();
        /** != null solo en warm-up: retiene los batches completos para el estado inicial. */
        private final Map<String, LuggageBatch> completos;

        AcumuladorAuditoria(boolean retenerBatches) {
            this.completos = retenerBatches ? new LinkedHashMap<>() : null;
        }

        /** Registra el estado del batch en el bloque actual (snapshot ligero; dedup por clave). */
        void registrar(LuggageBatch b) {
            String key = batchAuditKey(b);
            if (completos != null) { completos.put(key, b); return; }   // warm-up: solo completos
            resumen.put(key, ResumenEnvio.de(b));
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (enrutada) sinRuta.remove(key);              // pasó de sin-ruta a enrutado
            else sinRuta.put(key, b.cloneBatch());          // snapshot ligero (sin ruta) para el ZIP
        }

        boolean isEmpty()                    { return resumen.isEmpty(); }
        Collection<LuggageBatch> sinRuta()   { return sinRuta.values(); }
        Collection<LuggageBatch> completos() { return completos != null ? completos.values() : List.of(); }

        TotalesUnicos totalesUnicos() {
            if (resumen.isEmpty()) return new TotalesUnicos(0, 0, 0, 0, 0, 0L);
            int envios = resumen.size();
            int enrutadas = 0, cumpleSLA = 0;
            long maletas = 0L;
            for (ResumenEnvio r : resumen.values()) {
                maletas += r.quantity();
                if (r.enrutada()) { enrutadas++; if (r.cumpleSLA()) cumpleSLA++; }
            }
            int tardadas = enrutadas - cumpleSLA;
            int sinRutaN = envios - enrutadas;
            return new TotalesUnicos(envios, enrutadas, sinRutaN, cumpleSLA, tardadas, maletas);
        }

        // El corte de "entregadas" es el RELOJ UTC de la simulación: el máximo readyTime (UTC,
        // normalizado por AlgorithmMapper) visto en el acumulador — el mismo concepto de reloj que
        // usa reconstruirEsperaOrigenBacklog. Es físico y monótono; si no entran envíos nuevos el
        // reloj se detiene en el último registro (subcuenta conservadora, nunca cuenta una entrega
        // futura).
        void llenarAcumuladosFisicos(BloqueSimulacion bloque) {
            if (bloque == null || resumen.isEmpty()) return;
            long corteMin = Long.MIN_VALUE;
            for (ResumenEnvio r : resumen.values()) if (r.readyMin() > corteMin) corteMin = r.readyMin();

            long procesadas = 0L, enrutadas = 0L, entregadas = 0L;
            for (ResumenEnvio r : resumen.values()) {
                procesadas += r.quantity();
                if (!r.enrutada()) continue;
                enrutadas += r.quantity();
                if (r.ultimoArriboMin() <= corteMin) entregadas += r.quantity();
            }
            bloque.setMaletasProcesadasAcum(procesadas);
            bloque.setMaletasEnrutadasAcum(enrutadas);
            bloque.setMaletasEntregadasAcum(entregadas);
        }
    }

    /** Fase 5b-2: snapshot de métricas por bloque para el /dashboard mientras el job corre (las
     *  asignaciones de bloques viejos se purgan, así que no se pueden recontar). */
    private static com.tasfb2b.planificador.dto.Metricas metricasSnapshotDe(TotalesUnicos t, long taPromedioMs) {
        com.tasfb2b.planificador.dto.Metricas m = new com.tasfb2b.planificador.dto.Metricas();
        m.setProcesadas(t.envios());
        m.setEnrutadas(t.enrutadas());
        m.setSinRuta(t.sinRuta());
        m.setCumpleSLA(t.cumpleSLA());
        m.setTardadas(t.tardadas());
        m.setMaletasIndividuales(t.maletas());
        m.setTaPromedioMs(taPromedioMs);
        return m;
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
                SimulacionFormat.safe(b.getId()),
                SimulacionFormat.safe(b.getOriginCode()),
                SimulacionFormat.safe(b.getDestCode()),
                b.getReadyTime() != null ? b.getReadyTime().toString() : "",
                String.valueOf(b.getQuantity()));
    }

    private static void llenarMetricasBacklog(Metricas m, BacklogManager backlog) {
        m.setBacklogActual(backlog.size());
        m.setBacklogPico(backlog.picoHistorico());
        m.setSinRutaDefinitivo(backlog.sinRutaDefinitivo());
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
            // dataLoader puede ser null en tests unitarios sin Spring: offset 0 (UTC = local).
            List<Aeropuerto> aeropuertos = dataLoader != null ? dataLoader.getAeropuertos() : List.of();
            for (Aeropuerto a : aeropuertos) {
                if (a.getCodigo() != null && a.getOffsetHorario() != null) {
                    mapa.put(a.getCodigo(), a.getOffsetHorario());
                }
            }
            offsetPorCodigo = mapa;
        }
        return mapa.getOrDefault(codigo, 0);
    }

    private void agregarInfoAeropuerto(Map<String, AeropuertoDTO> map,
                                       String cod, Aeropuerto a) {
        if (!map.containsKey(cod)) {
            AeropuertoDTO dto = new AeropuertoDTO();
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
            BloqueSimulacion bloque,
            int envios, int enrutadas, int sinRuta, int cumpleSLA, int tardadas, long maletas,
            boolean colapsoAlmacen, String detalleColapso,
            com.tasfb2b.planificador.dto.AlertaColapso alerta,
            List<OcupacionAlmacenSlot> serieAlmacenes,
            List<LuggageBatch> finalBatches) {
    }

    private record TotalesUnicos(
            int envios, int enrutadas, int sinRuta, int cumpleSLA, int tardadas, long maletas) {
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
    }
}
