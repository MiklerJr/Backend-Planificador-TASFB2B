package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.aco.*;
import com.tasfb2b.planificador.algoritmo.alns.*;
import com.tasfb2b.planificador.algoritmo.grafo.*;
import com.tasfb2b.planificador.servicios.jobs.*;
import com.tasfb2b.planificador.servicios.persistencia.*;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.dto.jobs.AlertaColapso;
import com.tasfb2b.planificador.dto.auditoria.AuditoriaEnvio;
import com.tasfb2b.planificador.dto.vuelos.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.simulacion.EjecucionParametros;
import com.tasfb2b.planificador.dto.almacenes.*;
import com.tasfb2b.planificador.dto.auditoria.*;
import com.tasfb2b.planificador.dto.datos.*;
import com.tasfb2b.planificador.dto.jobs.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.dto.vuelos.VueloCancelado;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Envio;
import com.tasfb2b.planificador.dto.vuelos.VuelosUsadosResponse;
import com.tasfb2b.planificador.utilidades.MapeadorAlgoritmo;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.FragmentadorEnvios;
import com.tasfb2b.planificador.utilidades.FormatoSimulacion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlanificadorService {

    private final CargadorDatos cargadorDatos;
    private final MapeadorAlgoritmo mapper;
    private final PlanificadorProperties props;
    private final RegistroJobs jobs;
    private final AuditoriaService auditoria;
    private final ColoniaACO acoEngine;
    private final PersistenciaSolucionService persistencia;
    private final LectorSolucionBd solucionBdReader;
    private final MotorGrafoCache motorCache;
    private final AlmacenCacheEsqueletos almacenEsqueletos;
    private final ConfiguracionCapacidadesService configCapacidades;
    private final AltasEnCalienteService altasEnCaliente;
    private final CacheOffsetsAeropuerto cacheOffsets;
    private final TelemetriaSimulacionService telemetria;
    private final OperacionesEnVivoService operacionesEnVivo;

    public static final String MOTOR_ALNS = "alns";
    public static final String MOTOR_ACO  = "aco";

    private static final int PREWARM_ROUTE_CANDIDATES = 5;

    private static final int SUFIJO_ROUTE_CANDIDATES = 5;

    private volatile List<BloqueSimulacion> bloquesCacheados = null;

    // CONSTRUCTOR UNIFICADO
    @org.springframework.beans.factory.annotation.Autowired
    public PlanificadorService(CargadorDatos cargadorDatos,
                               MapeadorAlgoritmo mapper,
                               PlanificadorProperties props,
                               RegistroJobs jobs,
                               AuditoriaService auditoria,
                               ColoniaACO acoEngine,
                               PersistenciaSolucionService persistencia,
                               LectorSolucionBd solucionBdReader,
                               MotorGrafoCache motorCache,
                               AlmacenCacheEsqueletos almacenEsqueletos,
                               ConfiguracionCapacidadesService configCapacidades,
                               AltasEnCalienteService altasEnCaliente,
                               CacheOffsetsAeropuerto cacheOffsets,
                               TelemetriaSimulacionService telemetria,
                               OperacionesEnVivoService operacionesEnVivo) {
        this.cargadorDatos = cargadorDatos;
        this.mapper = mapper;
        this.props = props;
        this.jobs = jobs;
        this.auditoria = auditoria;
        this.acoEngine = acoEngine;
        this.persistencia = persistencia;
        this.solucionBdReader = solucionBdReader;
        this.motorCache = motorCache;
        this.almacenEsqueletos = almacenEsqueletos;
        this.configCapacidades = configCapacidades;
        this.altasEnCaliente = altasEnCaliente;
        this.cacheOffsets = cacheOffsets;
        this.telemetria = telemetria;
        this.operacionesEnVivo = operacionesEnVivo;
    }

    PlanificadorService(CargadorDatos cargadorDatos, MapeadorAlgoritmo mapper, PlanificadorProperties props,
                        RegistroJobs jobs, AuditoriaService auditoria,
                        ColoniaACO acoEngine) {
        this(cargadorDatos, mapper, props, jobs, auditoria, acoEngine,
                new PersistenciaSolucionService(null, null), new LectorSolucionBd(null, null, null),
                new MotorGrafoCache(), new AlmacenCacheEsqueletos(null, null, ""), null, null,
                new CacheOffsetsAeropuerto(cargadorDatos), new TelemetriaSimulacionService(),
                new OperacionesEnVivoService(jobs, cargadorDatos, null, null,
                        new PersistenciaSolucionService(null, null), props));
    }

    private void resetearCapacidadesAlIniciarCorrida() {
        if (configCapacidades != null) {
            configCapacidades.resincronizarCapacidadesConBaselineFrio();
        }
    }

    public EstadoJob iniciarEscenario2Async(EjecucionParametros params) {
        if (params == null) params = new EjecucionParametros();
        int k = props.getScenario().getKDefault2();
        String motorRes = resolverMotor(params.getMotor());
        long seedRes = resolverSeed(params.getSeed());

        EstadoJob job = jobs.crear("2", k);
        job.setMaxBloquesConAsignaciones(props.getScenario().getMaxBloquesBuffer());
        job.algoritmo = motorRes;
        job.seed = seedRes;
        job.fechaInicio = params.getFechaInicio();
        job.saMin = params.getSaMin();
        job.taSegundos = params.getTaSegundos();
        job.dias = params.getDias();
        job.procesamientoPrevio = params.isProcesamientoPrevio();

        EjecucionParametros pf = params;
        pf.setK(k);
        pf.setMotor(motorRes);
        pf.setSeed(seedRes);

        jobs.ejecutar(job, () -> {
            try {
                SimulacionResponse res = ejecutarALNS(pf, job);
                job.resultado = res;
            } finally {

                almacenEsqueletos.guardarSiCrecio();
            }
        });
        return job;
    }

    public EstadoJob iniciarEscenario3Async(double umbralColapso, String motor, Long seed) {
        return iniciarEscenario3Async(umbralColapso, motor, seed, null);
    }

    public EstadoJob iniciarEscenario3Async(double umbralColapso, String motor, Long seed,
                                           LocalDateTime fechaInicio) {
        int k = props.getScenario().getKDefault3();
        String motorRes = resolverMotor(motor);
        long seedRes = resolverSeed(seed);
        EstadoJob job = jobs.crear("3", k);
        job.setMaxBloquesConAsignaciones(props.getScenario().getMaxBloquesBuffer());
        job.algoritmo = motorRes;
        job.seed = seedRes;
        job.fechaInicio = fechaInicio;
        job.umbralColapso = umbralColapso;
        jobs.ejecutar(job, () -> {
            try {
                SimulacionResponse res = ejecutarHastaColapso(k, umbralColapso, job, motorRes, seedRes, fechaInicio);
                job.resultado = res;
            } finally {
                almacenEsqueletos.guardarSiCrecio();
            }
        });
        return job;
    }

    public EstadoJob iniciarEscenario1Async(String motor, Long seed) {
        return iniciarEscenario1Async(motor, seed, null);
    }

    public EstadoJob iniciarEscenario1Async(String motor, Long seed, LocalDateTime fechaInicio) {
        return iniciarEscenario1Async(motor, seed, fechaInicio, false);
    }

    public EstadoJob iniciarEscenario1Async(String motor, Long seed, LocalDateTime fechaInicio,
                                           boolean enVivo) {
        String motorRes = resolverMotor(motor);
        long seedRes = resolverSeed(seed);
        int k = props.getScenario().getKDefault1();
        EstadoJob job = jobs.crear("1", k);
        job.setMaxBloquesConAsignaciones(props.getScenario().getMaxBloquesBuffer());   // anti-OOM (Fase 1)
        job.algoritmo = motorRes;
        job.seed = seedRes;
        job.fechaInicio = enVivo ? null : fechaInicio;
        job.enVivo = enVivo;
        final LocalDateTime fechaEff = job.fechaInicio;
        jobs.ejecutar(job, () -> {
            try {
                SimulacionResponse res = ejecutarEscenario1(job, motorRes, seedRes, fechaEff, enVivo);
                job.resultado = res;
            } finally {
                almacenEsqueletos.guardarSiCrecio();
            }
        });
        return job;
    }

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
        if (fechaInicio != null && cargadorDatos != null) {
            LocalDateTime primera = cargadorDatos.getPrimeraVentana();
            LocalDateTime ultima = cargadorDatos.getUltimaVentana();
            if (primera != null && ultima != null
                    && (fechaInicio.isBefore(primera) || !fechaInicio.isBefore(ultima))) {
                return "fechaInicio fuera del rango del dataset [" + primera + ", " + ultima + ")";
            }
        }
        return null;
    }

    private static long resolverSeed(Long seed) {
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

    public EstadoJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    public boolean cancelarJob(String jobId) {
        return jobs.cancelar(jobId);
    }

    public EstadoJob reiniciarJob(String jobId) {
        EstadoJob viejo = getJob(jobId);
        if (viejo == null) return null;
        if (RegistroJobs.ESTADOS_ACTIVOS.contains(viejo.estado)) {
            cancelarJob(jobId);
        }
        return switch (viejo.getEscenario()) {
            case "1" -> iniciarEscenario1Async(viejo.algoritmo, viejo.seed, viejo.fechaInicio, viejo.enVivo);
            case "2" -> {
                EjecucionParametros p = new EjecucionParametros();
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

    public List<EstadoJob> listarJobsActivos() {
        return jobs.listarActivos();
    }

    public List<EstadoJob> listarTodosLosJobs() {
        return jobs.listarTodos();
    }

    public ListaJobsResponse listarJobsResponse(boolean activos) {
        List<EstadoJob> lista = activos ? listarJobsActivos() : listarTodosLosJobs();
        List<ListaJobsResponse.ResumenJob> items = new ArrayList<>(lista.size());
        for (EstadoJob j : lista) {
            ListaJobsResponse.ResumenJob item = new ListaJobsResponse.ResumenJob();
            item.setJobId(j.getJobId());
            item.setEscenario(j.getEscenario());
            item.setAlgoritmo(j.algoritmo);
            item.setEstado(j.estado);
            item.setEnVivo(j.enVivo);
            item.setK(j.getK());
            item.setSeed(j.seed);
            if (j.fechaInicio != null) item.setFechaInicio(j.fechaInicio.toString());
            item.setInicio(j.inicio.toString());
            if (j.fin != null) item.setFin(j.fin.toString());
            item.setProgreso(j.getProgreso());
            item.setProgresoWarmup(j.getProgresoWarmup());
            items.add(item);
        }
        ListaJobsResponse body = new ListaJobsResponse();
        body.setJobs(items);
        body.setTotal(items.size());
        return body;
    }

    public int posicionEnCola(String jobId) {
        return jobs.posicionEnCola(jobId);
    }

                public SimulacionResponse ejecutarALNS(int k) {
        return ejecutarALNS(k, null, MOTOR_ALNS, resolverSeed(null), null);
    }

    public SimulacionResponse ejecutarALNS(int k, EstadoJob job, String motor,
                                            long seed, LocalDateTime fechaInicio) {
        EjecucionParametros p = new EjecucionParametros();
        p.setK(k);
        p.setMotor(motor);
        p.setSeed(seed);
        p.setFechaInicio(fechaInicio);
        return ejecutarALNS(p, job);
    }

    public SimulacionResponse ejecutarALNS(EjecucionParametros params, EstadoJob job) {
        if (params == null) params = new EjecucionParametros();
        int k = params.getK() != null ? params.getK() : props.getScenario().getKDefault2();
        String motor = params.getMotor();
        long seed = resolverSeed(params.getSeed());
        LocalDateTime fechaInicio = params.getFechaInicio();
        Integer saOverride = params.getSaMin();
        Integer taOverride = params.getTaSegundos();
        Integer diasOverride = params.getDias();

        String motorRes = resolverMotor(motor);
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

        List<ContextoTemporal> plan = construirPlanBloques(k, fechaInicio, saOverride, diasOverride);
        if (plan.isEmpty()) {
            return respuestaVacia(k, saMin);
        }

        List<ContextoTemporal> warmupPlan = params.isProcesamientoPrevio()
                ? construirPlanWarmup(k, fechaInicio, saOverride)
                : Collections.emptyList();

        return ejecutarBucle(EspecificacionEscenario.paraE2(k, saMin, taFijoMs, plan, warmupPlan,
                props.getScenario().isSimularTiempoReal2(), motorRes, seed, fechaInicio), job, inicio);
    }

    public BloqueSimulacion getBloque(int index) {
        if (bloquesCacheados == null || index < 0 || index >= bloquesCacheados.size()) return null;
        return bloquesCacheados.get(index);
    }

    public SimulacionResponse ejecutarEscenario1(EstadoJob job, String motor, long seed,
                                                 LocalDateTime fechaInicio, boolean enVivo) {
        String motorRes = resolverMotor(motor);
        int k = props.getScenario().getKDefault1();
        int saMin = props.getScenario().getSaMinutos();
        long taFijoMs = props.getScenario().getTaSegundos() * 1000L;
        int scMin = Math.max(saMin, k * saMin);
        log.info("Escenario 1 — motor={} seed={} (K={}, Sa={}min, Sc={}min, fechaInicio={}, enVivo={}, async={}) ...",
                motorRes, seed, k, saMin, scMin, fechaInicio, enVivo, job != null);
        long inicio = System.currentTimeMillis();

        List<ContextoTemporal> plan = enVivo
                ? construirPlanOperacionE1(k)
                : construirPlanBloques(k, fechaInicio);
        List<ContextoTemporal> warmupPlan = (!enVivo && fechaInicio != null)
                ? construirPlanWarmup(k, fechaInicio, null)
                : Collections.emptyList();
        if (plan.isEmpty()) {
            return respuestaVacia(k, saMin);
        }

        return ejecutarBucle(EspecificacionEscenario.paraE1(k, saMin, taFijoMs, plan, warmupPlan,
                enVivo, enVivo || props.getScenario().isSimularTiempoReal1(), motorRes, seed, fechaInicio),
                job, inicio);
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso) {
        return ejecutarHastaColapso(k, umbralColapso, null, MOTOR_ALNS, resolverSeed(null), null);
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso,
                                                   EstadoJob job, String motor, long seed,
                                                   LocalDateTime fechaInicio) {
        String motorRes = resolverMotor(motor);
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        int saMin = props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);
        log.info("Escenario 3 — colapso motor={} seed={} (K={}, Sa={}min, Sc={}min, umbral={}%, fechaInicio={}, async={}) ...",
                motorRes, seed, k, saMin, scMin,
                String.format("%.1f", umbralColapso * 100),
                fechaInicio, job != null);
        long inicio = System.currentTimeMillis();

        List<ContextoTemporal> plan = construirPlanBloquesHastaColapso(k, fechaInicio);
        List<ContextoTemporal> warmupPlan = fechaInicio != null
                ? construirPlanWarmup(k, fechaInicio, null)
                : Collections.emptyList();
        if (plan.isEmpty()) {
            return respuestaVacia(k, saMin);
        }

        return ejecutarBucle(EspecificacionEscenario.paraE3(k, saMin,
                props.getScenario().getTaSegundos() * 1000L, plan, warmupPlan,
                props.getScenario().isSimularTiempoReal3(), motorRes, seed, fechaInicio), job, inicio);
    }

    /**
     * Parámetros que distinguen a los tres escenarios dentro del bucle unificado de bloques
     * (Tanda 4F-3). Cada {@code ejecutarX} construye su spec vía fábrica y delega en
     * {@link #ejecutarBucle}; los textos de log conservan la etiqueta y formatos históricos.
     */
    private record EspecificacionEscenario(
            String etiqueta,                     // "E1"/"E2"/"E3" — prefijo de todos los logs
            int k,
            int saMin,
            long taFijoMs,                       // presupuesto Ta del warm-up y (salvo E3) del bucle
            long taProcesarBloqueMs,             // override para procesarBloque (E3: 0L ⇒ usa props)
            List<ContextoTemporal> plan,
            List<ContextoTemporal> warmupPlan,
            boolean warmupCadenciaTaFija,        // E3: cada bloque de warm-up consume su Ta completo
            boolean iniciarCorridaAntesDeWarmup, // E1: orden histórico del TRUNCATE por corrida
            boolean preWarmEsqueletos,           // E2: pre-warm Fase T + cancelación pre-bucle
            boolean aplicarInyecciones,          // E1: drena inyecciones EN VIVO por bloque
            boolean demandaEnVivo,               // E1 enVivo: la demanda entra solo por inyección
            boolean setTiempoProcesamiento,      // E1/E3: taMs por bloque visible en el DTO
            boolean contarVuelosCancelados,      // E1/E2: el total va a métricas (E3 publica 0)
            boolean pararPorBacklog,             // E3: SLA vencido en backlog ⇒ colapso definitivo
            boolean logSaturacionCada50,         // E2
            boolean logProgresoDebug,            // E1/E2
            boolean logDiagnosticosAlFinal,      // E1/E2
            String calibrarQue,                  // aviso Ta>Sa: "K" (E2/E3) o "Ta" (E1)
            boolean simularTiempoReal,
            String motorRes,
            long seed,
            LocalDateTime fechaInicio) {

        static EspecificacionEscenario paraE2(int k, int saMin, long taFijoMs,
                                              List<ContextoTemporal> plan, List<ContextoTemporal> warmupPlan,
                                              boolean simularTiempoReal, String motorRes, long seed,
                                              LocalDateTime fechaInicio) {
            return new EspecificacionEscenario("E2", k, saMin, taFijoMs, taFijoMs, plan, warmupPlan,
                    false,  // warmupCadenciaTaFija
                    false,  // iniciarCorridaAntesDeWarmup
                    true,   // preWarmEsqueletos
                    false,  // aplicarInyecciones
                    false,  // demandaEnVivo
                    false,  // setTiempoProcesamiento
                    true,   // contarVuelosCancelados
                    false,  // pararPorBacklog
                    true,   // logSaturacionCada50
                    true,   // logProgresoDebug
                    true,   // logDiagnosticosAlFinal
                    "K", simularTiempoReal, motorRes, seed, fechaInicio);
        }

        static EspecificacionEscenario paraE1(int k, int saMin, long taFijoMs,
                                              List<ContextoTemporal> plan, List<ContextoTemporal> warmupPlan,
                                              boolean enVivo, boolean simularTiempoReal, String motorRes,
                                              long seed, LocalDateTime fechaInicio) {
            return new EspecificacionEscenario("E1", k, saMin, taFijoMs, taFijoMs, plan, warmupPlan,
                    false,  // warmupCadenciaTaFija
                    true,   // iniciarCorridaAntesDeWarmup
                    false,  // preWarmEsqueletos
                    true,   // aplicarInyecciones
                    enVivo, // demandaEnVivo
                    true,   // setTiempoProcesamiento
                    true,   // contarVuelosCancelados
                    false,  // pararPorBacklog
                    false,  // logSaturacionCada50
                    true,   // logProgresoDebug
                    true,   // logDiagnosticosAlFinal
                    "Ta", simularTiempoReal, motorRes, seed, fechaInicio);
        }

        static EspecificacionEscenario paraE3(int k, int saMin, long taFijoMs,
                                              List<ContextoTemporal> plan, List<ContextoTemporal> warmupPlan,
                                              boolean simularTiempoReal, String motorRes, long seed,
                                              LocalDateTime fechaInicio) {
            return new EspecificacionEscenario("E3", k, saMin, taFijoMs, 0L, plan, warmupPlan,
                    true,   // warmupCadenciaTaFija
                    false,  // iniciarCorridaAntesDeWarmup
                    false,  // preWarmEsqueletos
                    false,  // aplicarInyecciones
                    false,  // demandaEnVivo
                    true,   // setTiempoProcesamiento
                    false,  // contarVuelosCancelados
                    true,   // pararPorBacklog
                    false,  // logSaturacionCada50
                    false,  // logProgresoDebug
                    false,  // logDiagnosticosAlFinal
                    "K", simularTiempoReal, motorRes, seed, fechaInicio);
        }
    }

    /** Bucle de bloques unificado de E1/E2/E3 (Tanda 4F-3). Behavior-preserving: mismo orden de
     *  operaciones, mismos textos de log y mismo consumo del Random que los tres bucles históricos. */
    private SimulacionResponse ejecutarBucle(EspecificacionEscenario spec, EstadoJob job, long inicio) {
        final String etiqueta = spec.etiqueta();
        final List<ContextoTemporal> plan = spec.plan();
        final int k = spec.k();
        final int saMin = spec.saMin();
        final String motorRes = spec.motorRes();
        final long seed = spec.seed();

        MotorCorrida motorCorrida = prepararMotorCorrida();
        Grafo graph = motorCorrida.graph();
        OperadorReparacionVoraz enrutador = motorCorrida.enrutador();
        SolucionAlns solucionDummy = motorCorrida.solucionDummy();

        int totalBloques = plan.size();
        int intervaloReporte = Math.max(1, totalBloques / 10);

        int totalVuelosCancelados = 0;
        List<VueloCancelado> vuelosCancelados = job != null ? job.getVuelosCancelados() : new ArrayList<>();
        List<CancelacionVueloRequest> cancelacionesNoAplicadas =
                job != null ? job.getCancelacionesNoAplicadas() : new ArrayList<>();
        List<InyeccionEnviosRequest.Item> bufferInyecciones = new ArrayList<>();

        List<BloqueSimulacion> bloques = new ArrayList<>(totalBloques);
        Map<String, int[]> odStats = new HashMap<>();
        int totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
                totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        EstadisticasTa taStats = new EstadisticasTa();
        long saMs = saMin * 60_000L;
        GestorBacklog backlog = crearBacklogConPurga(enrutador);
        AcumuladorAuditoria auditAcc = new AcumuladorAuditoria(false);

        boolean colapsoDetectado = false;
        int bloqueColapso = -1;
        String detalleColapso = null;
        LocalDateTime instanteColapso = null;
        String motivoParada = "falta_datos";
        String nivelAlertaPrevio = AlertaColapso.VERDE;

        if (spec.iniciarCorridaAntesDeWarmup()) {
            persistencia.iniciarCorrida(job != null ? job.getJobId() : null);
        }

        AcumuladorAuditoria auditWarmup = ejecutarWarmup(spec.warmupPlan(), job, graph, enrutador,
                solucionDummy, odStats, backlog, motorRes, seed, spec.taFijoMs(), spec.fechaInicio(),
                spec.warmupCadenciaTaFija());
        if (job != null) job.estadoInicial = telemetria.construirEstadoInicial(auditWarmup.completos());

        if (spec.preWarmEsqueletos() && props.getScenario().isPrewarmSkeletons() && !plan.isEmpty()) {
            long t0Prewarm = System.currentTimeMillis();
            List<Envio> demandaVentana = cargadorDatos.getMaletasEnRango(
                    plan.get(0).scInicio, plan.get(plan.size() - 1).scFin);
            if (job != null && !cancelacionPedida(job)) job.estado = "calentando";
            log.info("Pre-warm iniciado: {} envíos en ventana | caché de esqueletos con {} claves precargadas",
                    demandaVentana.size(), motorCache.cacheEsqueletos().size());
            int clavesCalentadas = enrutador.precalentarEsqueletos(
                    mapper.mapearALotes(demandaVentana), PREWARM_ROUTE_CANDIDATES,
                    () -> cancelacionPedida(job));
            log.info("Pre-warm esqueletos (N3): {} claves desde {} envíos en {} ms",
                    clavesCalentadas, demandaVentana.size(), System.currentTimeMillis() - t0Prewarm);
            if (job != null && !cancelacionPedida(job)) job.estado = "ejecutando";
            almacenEsqueletos.guardarSiCrecio();
        }

        if (spec.preWarmEsqueletos() && cancelacionPedida(job)) {
            log.info("{} cancelado por usuario antes del primer bloque (warm-up/pre-warm)", etiqueta);
            return respuestaVacia(k, saMin);
        }

        if (!spec.iniciarCorridaAntesDeWarmup()) {
            persistencia.iniciarCorrida(job != null ? job.getJobId() : null);
        }

        for (ContextoTemporal ctx : plan) {
            bloqueActual++;
            operacionesEnVivo.aplicarAltasEnCaliente(job, graph, enrutador, ctx.bloqueIdx);
            int canceladosBloque = operacionesEnVivo.aplicarCancelacionesVuelo(
                    job != null ? job.getJobId() : null,
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, vuelosCancelados, cancelacionesNoAplicadas);
            if (spec.contarVuelosCancelados()) totalVuelosCancelados += canceladosBloque;
            if (spec.aplicarInyecciones() && job != null) {
                operacionesEnVivo.aplicarInyeccionesEnvio(job, bufferInyecciones, ctx, backlog, graph);
            }
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog,
                    auditAcc, motorRes, rngBloque, spec.taProcesarBloqueMs(), false, spec.demandaEnVivo());

            if (spec.setTiempoProcesamiento()) rv.bloque.setTiempoProcesamientoMs(ctx.taMs);

            bloques.add(rv.bloque);
            if (job != null && bloques.size() > job.getMaxBloquesConAsignaciones()) bloques.remove(0);
            taStats.acumular(ctx.taMs);

            TotalesUnicos totales = auditAcc.totalesUnicos();
            totalEnvios = totales.envios();
            totalEnrutadas = totales.enrutadas();
            totalSinRuta = totales.sinRuta();
            totalCumpleSLA = totales.cumpleSLA();
            totalTardadas = totales.tardadas();
            totalMaletas = totales.maletas();

            if (publicarBloqueYDetectarCancelacion(job, rv, bloqueActual, totalBloques, taStats, ctx, totales)) {
                motivoParada = "cancelado_front";
                log.info("{} cancelado por usuario en bloque {}/{}", etiqueta, bloqueActual, totalBloques);
                break;
            }
            nivelAlertaPrevio = avisarColapsoInminente(etiqueta, rv.alerta(), bloqueActual, nivelAlertaPrevio);

            if (bloqueActual < plan.size()) {
                double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
                plan.get(bloqueActual).tasaSinRutaPrevia = tasa;
            }

            int vencidos = backlog.purgarVencidas(ctx.scFin);
            boolean backlogDefinitivo = spec.pararPorBacklog() && vencidos > 0;

            logBloque(motorRes, bloqueActual, totalBloques,
                    rv.envios, rv.cumpleSLA, rv.tardadas, rv.sinRuta, ctx.taMs, backlog.tamaño(),
                    backlogDefinitivo || rv.colapsoAlmacen(), job, auditAcc.sinRutaSize());

            if (rv.colapsoAlmacen()) {
                colapsoDetectado = true;
                bloqueColapso = bloqueActual;
                motivoParada = "almacen_lleno";
                detalleColapso = rv.detalleColapso();
                instanteColapso = ctx.scFin;
                if (spec.pararPorBacklog()) {
                    log.warn("{} ALMACÉN LLENO en bloque {}/{} — envío {}",
                            etiqueta, bloqueActual, totalBloques, rv.detalleColapso());
                } else {
                    log.warn("{} COLAPSO por almacén lleno en bloque {}/{} — {}",
                            etiqueta, bloqueActual, totalBloques, rv.detalleColapso());
                }
                break;
            }

            if (backlogDefinitivo) {
                colapsoDetectado = true;
                bloqueColapso = bloqueActual;
                motivoParada = "backlog_definitivo";
                detalleColapso = vencidos + " envío(s) del backlog con SLA vencido";
                instanteColapso = ctx.scFin;
                break;
            }

            if (spec.logSaturacionCada50() && (bloqueActual % 50 == 0 || bloqueActual == totalBloques)) {
                log.info("--- Saturación tras bloque {}/{} ---", bloqueActual, totalBloques);
                enrutador.logEstadisticasCapacidad();
            }

            if (spec.logProgresoDebug() && log.isDebugEnabled()
                    && (bloqueActual % intervaloReporte == 0 || bloqueActual == totalBloques)) {
                log.debug("Progreso {} ({}): {}% — {}/{} | envíos:{} maletas:{} | ok:{} tarde:{} sinRuta:{} | Ta={}ms",
                        etiqueta, motorRes,
                        (int) Math.round(bloqueActual * 100.0 / totalBloques),
                        bloqueActual, totalBloques,
                        totalEnvios, totalMaletas,
                        totalCumpleSLA, totalTardadas, totalSinRuta, ctx.taMs);
            }

            if (dormirSaRestante(etiqueta, spec.calibrarQue(), spec.simularTiempoReal(),
                    bloqueActual, totalBloques, saMs, ctx.taMs)) break;
        }

        bloquesCacheados = bloques;
        long tiempoMs = System.currentTimeMillis() - inicio;
        if (spec.pararPorBacklog()) {
            log.info("{} {}: {} bloques | {} envíos | {} maletas | ok:{} tarde:{} sinRuta:{} | Ta(min/avg/max)={}/{}/{} ms | backlog: pico={} actual={} definitivo={} | {} ms",
                    etiqueta,
                    "backlog_definitivo".equals(motivoParada)
                            ? "BACKLOG DEFINITIVO en bloque " + bloqueColapso
                            : ("almacen_lleno".equals(motivoParada)
                                    ? "ALMACÉN LLENO en bloque " + bloqueColapso
                                    : ("cancelado_front".equals(motivoParada)
                                            ? "CANCELADO por front en bloque " + bloqueActual
                                            : "fin por falta de datos")),
                    bloqueActual, totalEnvios, totalMaletas,
                    totalCumpleSLA, totalTardadas, totalSinRuta,
                    taStats.min(), taStats.promedio(), taStats.max(),
                    backlog.picoHistorico(), backlog.tamaño(), backlog.sinRutaDefinitivo(), tiempoMs);
        } else {
            log.info("{} completado en {} ms — {} bloques | {} envíos | {} maletas | ok:{} tarde:{} sinRuta:{} | Ta(min/avg/max)={}/{}/{} ms (Sa={} ms) | backlog: pico={} actual={} definitivo={}",
                    etiqueta, tiempoMs, bloqueActual, totalEnvios, totalMaletas,
                    totalCumpleSLA, totalTardadas, totalSinRuta,
                    taStats.min(), taStats.promedio(), taStats.max(), saMs,
                    backlog.picoHistorico(), backlog.tamaño(), backlog.sinRutaDefinitivo());
            if (colapsoDetectado) {
                log.warn("{} detenido por COLAPSO de almacén en bloque {}", etiqueta, bloqueColapso);
            }
        }
        if (spec.logDiagnosticosAlFinal()) logDiagnosticos(odStats, graph, enrutador);

        return construirRespuestaFinal(job, auditAcc, backlog, taStats, tiempoMs, bloqueActual,
                plan.get(0).scInicio.toLocalDate(), k, saMin, saMs,
                totalEnvios, totalEnrutadas, totalSinRuta, totalCumpleSLA, totalTardadas, totalMaletas,
                spec.contarVuelosCancelados() ? totalVuelosCancelados : 0,
                colapsoDetectado, bloqueColapso,
                colapsoDetectado ? motivoParada : null, detalleColapso, instanteColapso);
    }

    private void finalizarAuditoriaDiferida(EstadoJob job, AcumuladorAuditoria auditAcc) {
        String jobId = job != null ? job.getJobId() : null;
        try {
            if (job != null && auditAcc != null) {
                job.auditoriaSinRuta = new ArrayList<>(auditAcc.sinRuta());
            }
        } finally {
            persistencia.finalizarCorrida(jobId);
        }
    }

    private ResultadoVentana procesarBloque(ContextoTemporal ctx,
                                            Grafo graph,
                                            OperadorReparacionVoraz enrutador,
                                            SolucionAlns solucionDummy,
                                            Map<String, int[]> odStats,
                                            GestorBacklog backlog,
                                            AcumuladorAuditoria auditAcc,
                                            String motor,
                                            Random rngSim,
                                            long taFijoMsOverride,
                                            boolean fastForward,
                                            boolean demandaEnVivo) {
        ctx.marcarInicio();

        List<Envio> maletasVentana = demandaEnVivo
                ? Collections.emptyList()
                : cargadorDatos.getMaletasEnRango(ctx.scInicio, ctx.scFin);
        int umbralFrag = FragmentadorEnvios.umbralEfectivo(props.getFragmentacion(), graph);
        int maxSublotes = props.getFragmentacion().getMaxSublotes();
        List<LoteEnvio> bloqueBatches = mapper.mapearALotes(maletasVentana, umbralFrag, maxSublotes);

        List<LoteEnvio> afectadosCrudos = new ArrayList<>();
        if (backlog != null) {
            List<LoteEnvio> pendientes = backlog.sacarPendientesUrgentes(
                    props.getBacklog().getMaxReprocesoPorBloque());

            List<LoteEnvio> normales = new ArrayList<>();
            for (LoteEnvio b : pendientes) {
                enrutador.removerEsperaOrigenBacklog(b);
                if (b.tienePrefijo() || enrutador.rutaUsaVueloCancelado(b)) {
                    afectadosCrudos.add(b);
                } else {
                    if (b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty()) {
                        enrutador.liberarDeGlobal(b);
                        b.limpiarRuta();
                    }
                    normales.add(b);
                }
            }
            if (!normales.isEmpty()) {
                bloqueBatches = new ArrayList<>(bloqueBatches.size() + normales.size());
                bloqueBatches.addAll(normales);
                bloqueBatches.addAll(mapper.mapearALotes(maletasVentana, umbralFrag, maxSublotes));
            }
        }

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        List<LoteEnvio> afectadosResueltos = new ArrayList<>();
        for (LoteEnvio b : afectadosCrudos) {
            afectadosResueltos.add(
                    operacionesEnVivo.reenrutarAfectadoDesdePosicion(b, ctx, graph, enrutador, blockFlight, blockAirport));
        }
        Map<Long, Integer> telemetryFlight = blockFlight;
        Map<Long, Integer> telemetryAirport = blockAirport;
        List<LoteEnvio> finalBatches;

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
                throw new IllegalStateException("ColoniaACO no inyectado — motor 'aco' no disponible");
            }
            acoEngine.procesar(graph, enrutador, bloqueBatches, blockFlight, blockAirport, rngSim, presupuestoMs);
            finalBatches = bloqueBatches;
            enrutador.confirmarBloque(blockFlight, blockAirport);
        } else {
            List<LoteEnvio> intra = new ArrayList<>();
            List<LoteEnvio> inter = new ArrayList<>();
            for (LoteEnvio b : bloqueBatches) {
                if (b.getHorasLimiteSla() <= 24) intra.add(b);
                else inter.add(b);
            }
            for (LoteEnvio b : intra) {
                if (System.nanoTime() >= deadlineMotorNs) break;
                enrutador.reparar(solucionDummy, List.of(b), blockFlight, blockAirport);
            }
            for (LoteEnvio b : inter) {
                if (System.nanoTime() >= deadlineMotorNs) break;
                enrutador.reparar(solucionDummy, List.of(b), blockFlight, blockAirport);
            }

            long restanteAlnsMs = Math.max(0L, (deadlineMotorNs - System.nanoTime()) / 1_000_000L);
            if (restanteAlnsMs > 0 && bloqueBatches.stream().anyMatch(b -> !b.isCumpleSLA())) {
                AlgoritmoALNS alns = new AlgoritmoALNS(
                        graph, enrutador, bloqueBatches, blockFlight, blockAirport, props);
                if (rngSim != null) alns.setAleatorio(rngSim);

                double umbralCerca = 0.10;
                int iteraciones = (ctx.tasaSinRutaPrevia >= umbralCerca)
                        ? props.getAlns().getIteracionesCercaColapso()
                        : props.getAlns().getIteracionesBase();

                alns.tiempoLimiteMs = restanteAlnsMs;

                alns.ejecutar(iteraciones);
                finalBatches = alns.getMejorSolucion().getLotes();
                telemetryFlight = alns.getMejorBloqueVuelo();
                telemetryAirport = alns.getMejorBloqueAeropuerto();
                enrutador.confirmarBloque(telemetryFlight, telemetryAirport);
            } else {
                finalBatches = bloqueBatches;
                enrutador.confirmarBloque(blockFlight, blockAirport);
            }
        }

        if (!afectadosResueltos.isEmpty()) {
            finalBatches = new ArrayList<>(finalBatches);
            finalBatches.addAll(afectadosResueltos);
        }

        if (taFijoMs > 0 && !fastForward) {
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
                log.warn("Bloque {} excedió Ta: {}ms > {}ms (motor={})",
                        ctx.bloqueIdx, transcurridoMs, taFijoMs, motor);
            }
        }

        List<AsignacionMaleta> asignaciones = telemetria.buildAsignaciones(finalBatches);

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

        boolean colapsoAlmacen = false;
        String detalleColapso = null;
        for (LoteEnvio b : finalBatches) {
            boolean enrutada = b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty();
            if (enrutada && b.isCumpleSLA()) continue;
            if (enrutador.sinRutaPorAlmacenLleno(b)) {
                colapsoAlmacen = true;
                detalleColapso = b.getId() + " " + b.getCodigoOrigen() + "->" + b.getCodigoDestino()
                        + (enrutada ? " (desviado tardío por almacén lleno)" : "");
                break;
            }
        }

        if (backlog != null) {
            boolean motorAco = MOTOR_ACO.equalsIgnoreCase(motor);
            double umbralSlack = props.getBacklog().getUmbralReplanificacionSlack();
            for (LoteEnvio b : finalBatches) {
                boolean enrutada = b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty();
                if (!enrutada) {
                    backlog.agregarSinRuta(b);
                } else if (!motorAco && b.isCumpleSLA() && b.getRatioHolguraSla() < umbralSlack) {
                    backlog.agregarReplanificable(b);
                }
            }
            LoteEnvio origenDesbordado = enrutador.reconstruirEsperaOrigenBacklog(
                    backlog.verPendientes(), bloqueBatches);
            if (origenDesbordado != null && !colapsoAlmacen) {
                colapsoAlmacen = true;
                detalleColapso = "origen lleno " + origenDesbordado.getId()
                        + " @" + origenDesbordado.getCodigoOrigen();
            }
        }

        if (auditAcc != null) {
            for (LoteEnvio b : finalBatches) auditAcc.registrar(b);
        }

        ctx.marcarFin(taFijoMs);

        BloqueSimulacion bloque = new BloqueSimulacion();
        bloque.setHoraInicio(ctx.scInicio.toString());
        bloque.setHoraFin(ctx.scFin.toString());
        bloque.setHoraInicioUtc(ctx.scInicio.toString());
        bloque.setHoraFinUtc(ctx.scFin.toString());
        bloque.setMaletasProcesadas(finalBatches.size());
        bloque.setMaletasEnrutadas(enrutadas);
        bloque.setAsignaciones(asignaciones);
        bloque.setCargasVuelos(telemetria.buildCargasVuelos(telemetryFlight, graph, enrutador));
        bloque.setOcupacionAlmacenes(telemetria.buildOcupacionAlmacenes(telemetryAirport, graph, enrutador));
        bloque.setAlertaAlmacen(TelemetriaSimulacionService.construirAlertaAlmacen(bloque.getOcupacionAlmacenes(), ctx.bloqueIdx));
        bloque.setBloqueIdx(ctx.bloqueIdx);
        bloque.setTaMs(ctx.taMs);
        bloque.setScMinutos(ctx.scMinutos);
        if (auditAcc != null) auditAcc.llenarAcumuladosFisicos(bloque);

        var pre = enrutador.evaluarPreColapso(
                telemetryAirport, backlog != null ? backlog.verPendientes() : java.util.List.of());
        com.tasfb2b.planificador.dto.jobs.AlertaColapso alerta = telemetria.construirAlertaColapso(pre, ctx.bloqueIdx);

        if (!colapsoAlmacen && pre.utilAlmacenMax() > 1.0) {
            colapsoAlmacen = true;
            detalleColapso = "desborde de almacén " + pre.almacenCritico() + " al "
                    + Math.round(pre.utilAlmacenMax() * 100.0) + "% de capacidad";
        }

        List<OcupacionAlmacenSlot> serieAlmacenes = fastForward
                ? List.of()
                : telemetria.buildSerieAlmacenes(telemetryAirport, graph, enrutador);

        return new ResultadoVentana(bloque, finalBatches.size(), enrutadas, sinRuta, cumpleSLA, tardadas, maletas,
                colapsoAlmacen, detalleColapso, alerta, serieAlmacenes, finalBatches);
    }

        private String avisarColapsoInminente(String escenario, AlertaColapso alerta, int bloque, String nivelPrevio) {
        if (alerta == null) return nivelPrevio;
        String nivel = alerta.getNivel();
        if (!AlertaColapso.VERDE.equals(nivel) && !nivel.equals(nivelPrevio)) {
            log.warn("{} ⚠ COLAPSO INMINENTE [{}] bloque {} — {}", escenario, nivel, bloque, alerta.getMensaje());
        }
        return nivel;
    }

        private List<ContextoTemporal> construirPlanBloques(int k, LocalDateTime fechaInicio) {
        return construirPlanBloques(k, fechaInicio, null, null);
    }

    private List<ContextoTemporal> construirPlanOperacionE1(int k) {
        int saMin = props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);
        int horas = Math.max(1, props.getScenario().getOperacionHoras());

        LocalDateTime ahora = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime inicio = alinearASa(ahora, ahora.toLocalDate().atStartOfDay(), saMin);
        long ventanas = Math.max(1L, (long) horas * 60L / saMin);
        LocalDateTime fin = inicio.plusMinutes(ventanas * saMin);

        log.info("Plan operación E1 (EN VIVO): inicio={} fin={} K={} Sa={}min Sc={}min horizonte={}h",
                inicio, fin, k, saMin, scMin, horas);

        List<ContextoTemporal> plan = new ArrayList<>();
        LocalDateTime scInicio = inicio;
        int idx = 0;
        while (scInicio.isBefore(fin)) {
            LocalDateTime scFin = scInicio.plusMinutes(scMin);
            if (scFin.isAfter(fin)) scFin = fin;
            plan.add(new ContextoTemporal(scInicio, scFin, scMin, saMin, k, idx++));
            scInicio = scFin;
        }
        return plan;
    }

    private List<ContextoTemporal> construirPlanBloques(int k,
                                                        LocalDateTime fechaInicio,
                                                        Integer saMinOverride,
                                                        Integer diasOverride) {
        return construirPlanBloques(k, fechaInicio, saMinOverride, diasOverride,
                props.getScenario().getMaxVentanas());
    }

    private List<ContextoTemporal> construirPlanBloquesHastaColapso(int k, LocalDateTime fechaInicio) {
        return construirPlanBloques(k, fechaInicio, null, null,
                props.getScenario().getMaxVentanasColapso());
    }

    private List<ContextoTemporal> construirPlanBloques(int k,
                                                        LocalDateTime fechaInicio,
                                                        Integer saMinOverride,
                                                        Integer diasOverride,
                                                        int ventanasFallback) {
        LocalDateTime primero = cargadorDatos.getPrimeraVentana();
        LocalDateTime ultimo = cargadorDatos.getUltimaVentana();
        if (primero == null || ultimo == null) return Collections.emptyList();

        int saMin = (saMinOverride != null && saMinOverride > 0)
                ? saMinOverride
                : props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);

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

        long ventanasTotales;
        if (diasOverride != null && diasOverride > 0) {
            ventanasTotales = (long) diasOverride * 24L * 60L / saMin;
        } else {
            ventanasTotales = ventanasFallback;
        }

        LocalDateTime fin;
        if (ventanasTotales > 0) {
            fin = inicio.plusMinutes(ventanasTotales * saMin);
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

        List<ContextoTemporal> plan = new ArrayList<>();
        LocalDateTime scInicio = inicio;
        int idx = 0;
        while (scInicio.isBefore(fin)) {
            LocalDateTime scFin = scInicio.plusMinutes(scMin);
            if (scFin.isAfter(fin)) scFin = fin;
            plan.add(new ContextoTemporal(scInicio, scFin, scMin, saMin, k, idx++));
            scInicio = scFin;
        }
        return plan;
    }

    private List<ContextoTemporal> construirPlanWarmup(int k,
                                                       LocalDateTime fechaInicio,
                                                       Integer saMinOverride) {
        if (fechaInicio == null) return Collections.emptyList();
        LocalDateTime primero = cargadorDatos.getPrimeraVentana();
        LocalDateTime ultimo  = cargadorDatos.getUltimaVentana();
        if (primero == null || ultimo == null) return Collections.emptyList();

        int saMin = (saMinOverride != null && saMinOverride > 0)
                ? saMinOverride
                : props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);

        if (!fechaInicio.isAfter(primero)) return Collections.emptyList();
        if (!fechaInicio.isBefore(ultimo.plusMinutes(saMin))) return Collections.emptyList();

        LocalDateTime fin = alinearASa(fechaInicio, primero, saMin);
        if (!primero.isBefore(fin)) return Collections.emptyList();

        log.info("Plan warm-up: inicio={} fin={} K={} Sa={}min Sc={}min",
                primero, fin, k, saMin, scMin);

        List<ContextoTemporal> plan = new ArrayList<>();
        LocalDateTime scInicio = primero;
        int idx = 0;
        while (scInicio.isBefore(fin)) {
            LocalDateTime scFin = scInicio.plusMinutes(scMin);
            if (scFin.isAfter(fin)) scFin = fin;
            plan.add(new ContextoTemporal(scInicio, scFin, scMin, saMin, k, idx++));
            scInicio = scFin;
        }
        return plan;
    }

    private static LocalDateTime alinearASa(LocalDateTime t, LocalDateTime base, int saMin) {
        long minutosDesdeBase = java.time.Duration.between(base, t).toMinutes();
        long alineado = (minutosDesdeBase / saMin) * saMin;
        return base.plusMinutes(alineado);
    }

        private void logDiagnosticos(Map<String, int[]> odStats, Grafo graph, OperadorReparacionVoraz enrutador) {
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
        for (String code : graph.nodos.keySet()) {
            int sal = graph.getVecinos(code).size();
            if (sal == 0) {
                log.warn("  AISLADO: {}", code);
                sinSalida++;
            } else log.info("  {} → {} vuelos", code, sal);
        }
        if (sinSalida == 0) log.info("  Todos los aeropuertos tienen salidas.");
        enrutador.logEstadisticasCapacidad();
        log.info("=======================");
    }

                                        private AcumuladorAuditoria ejecutarWarmup(List<ContextoTemporal> warmupPlan, EstadoJob job,
                                                     Grafo graph, OperadorReparacionVoraz enrutador,
                                                     SolucionAlns solucionDummy, Map<String, int[]> odStats,
                                                     GestorBacklog backlog, String motorRes, long seed,
                                                     long taFijoMs, LocalDateTime fechaInicio,
                                                     boolean cadenciaTaFija) {
        AcumuladorAuditoria auditWarmup = new AcumuladorAuditoria(true);
        if (warmupPlan == null || warmupPlan.isEmpty()) return auditWarmup;

        if (job != null) {
            job.estado = "calentando";
            job.totalBloquesWarmup = warmupPlan.size();
            job.bloqueWarmup = 0;
        }
        long inicioWarmupMs = System.currentTimeMillis();
        log.info("Warm-up iniciado: {} bloques hasta fechaInicio={}", warmupPlan.size(), fechaInicio);
        int wIdx = 0;
        for (ContextoTemporal ctx : warmupPlan) {
            wIdx++;
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog,
                    auditWarmup, motorRes, rngBloque, taFijoMs, true, false);
            if (job != null) {
                job.bloqueWarmup = wIdx;
                if (("cancelado".equals(job.estado) || job.canceladoPorUsuario)) break;
            }
            log.info("Warm-up {}/{} [{}] | ventana {}→{} | envíos:{} | onTime:{} | tardadas:{} | sinRuta:{} | Ta real:{}ms | backlog:{}",
                    wIdx, warmupPlan.size(), motorRes, ctx.scInicio, ctx.scFin,
                    rv.envios, rv.cumpleSLA, rv.tardadas, rv.sinRuta, ctx.taRealMs, backlog.tamaño());
            if (cadenciaTaFija && taFijoMs > 0) {
                long restanteMs = taFijoMs - ctx.taRealMs;
                if (restanteMs > 0) {
                    try {
                        Thread.sleep(restanteMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Warm-up interrumpido en bloque {}/{}", wIdx, warmupPlan.size());
                        break;
                    }
                }
            }
        }
        log.info("Warm-up completado en {} ms (backlog={}, pico={})",
                System.currentTimeMillis() - inicioWarmupMs,
                backlog.tamaño(), backlog.picoHistorico());
        if (job != null && !("cancelado".equals(job.estado) || job.canceladoPorUsuario)) {
            job.estado = "ejecutando";
        }
        return auditWarmup;
    }

        private static GestorBacklog crearBacklogConPurga(OperadorReparacionVoraz enrutador) {
        return new GestorBacklog(0, true, b -> {
            if (enrutador.rutaUsaVueloCancelado(b)) {
                enrutador.liberarDeGlobal(b);
                b.limpiarRuta();
            }
        });
    }

    /** Motor recién construido para una corrida: grafo cacheado + enrutador con los tiempos operativos. */
    private record MotorCorrida(Grafo graph, OperadorReparacionVoraz enrutador, SolucionAlns solucionDummy) {}

    /**
     * Prepara el motor al iniciar una corrida (idéntico en E1/E2/E3): resincroniza capacidades al
     * baseline en frío, obtiene el grafo cacheado, crea un {@link OperadorReparacionVoraz} fresco y le
     * configura storage-aware + tiempos operativos (escala/recojo). Fuente única de esa configuración.
     */
    private MotorCorrida prepararMotorCorrida() {
        resetearCapacidadesAlIniciarCorrida();
        Grafo graph = motorCache.obtenerGrafo(
                () -> mapper.mapearAGrafo(cargadorDatos.getAeropuertos(), cargadorDatos.getVuelos()));
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph, motorCache.cacheEsqueletos());
        enrutador.configurarStorageAware(props.getStorageAware().getUmbralHubPico(),
                props.getStorageAware().getPrecioHubExponente());   // Fase P
        enrutador.configurarTiempoMinEscala(props.getOperativo().getTiempoMinEscalaMinutos());
        enrutador.configurarTiempoRecojoDestino(props.getOperativo().getTiempoRecojoDestinoMinutos());
        return new MotorCorrida(graph, enrutador, new SolucionAlns(Collections.emptyList()));
    }

    /** Respuesta vacía estándar cuando el plan no tiene bloques (o la corrida se canceló antes de empezar). */
    private SimulacionResponse respuestaVacia(int k, int saMin) {
        bloquesCacheados = new ArrayList<>();
        SimulacionResponse r = telemetria.construirRespuestaFront(0, 0L, cargadorDatos.getVuelos(), 0, null);
        r.setK(k);
        r.setSaMinutos(saMin);
        return r;
    }

    /**
     * Publica el bloque recién procesado al job (progreso, ventana, serie de almacenes, métricas,
     * alerta) y lo persiste a BD. Devuelve true si el usuario pidió cancelar la corrida
     * (el bucle llamador debe loguear su mensaje y salir).
     */
    private boolean publicarBloqueYDetectarCancelacion(EstadoJob job, ResultadoVentana rv,
                                                      int bloqueActual, int totalBloques,
                                                      EstadisticasTa taStats, ContextoTemporal ctx,
                                                      TotalesUnicos totales) {
        if (job == null) return false;
        job.bloqueActual = bloqueActual;
        job.totalBloques = totalBloques;
        job.taPromedioMs = taStats.promedio();
        job.registrarVentanaSimulada(ctx.scInicio, ctx.scFin);
        job.publicarBloque(rv.bloque);
        job.publicarSerieAlmacenes(rv.serieAlmacenes());
        job.metricasSnapshot = telemetria.metricasSnapshotDe(totales, taStats.promedio());
        job.alertaColapso = rv.alerta();
        persistencia.persistirBloque(job.getJobId(), rv.finalBatches());
        return "cancelado".equals(job.estado) || job.canceladoPorUsuario;
    }

    /**
     * Simulación a ritmo de reloj: duerme lo que resta de Sa tras el Ta del bloque.
     * Devuelve true si el hilo fue interrumpido (el bucle llamador debe salir).
     * El sleep/interrupt debe quedar aquí en posición idéntica a la original: la cancelación
     * de jobs depende de esta interrupción.
     */
    private boolean dormirSaRestante(String etiqueta, String calibrar, boolean simularTiempoReal,
                                     int bloqueActual, int totalBloques, long saMs, long taMs) {
        if (!simularTiempoReal || bloqueActual >= totalBloques) return false;
        long dormirMs = saMs - taMs;
        if (dormirMs > 0) {
            try {
                Thread.sleep(dormirMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("{} interrumpido en bloque {}/{}", etiqueta, bloqueActual, totalBloques);
                return true;
            }
        } else {
            log.warn("Ta={}ms > Sa={}ms en bloque {} — calibrar {} hacia abajo", taMs, saMs, bloqueActual, calibrar);
        }
        return false;
    }

    /** Tail común de los tres escenarios: respuesta al front + métricas + auditoría diferida. */
    private SimulacionResponse construirRespuestaFinal(EstadoJob job, AcumuladorAuditoria auditAcc,
                                                       GestorBacklog backlog, EstadisticasTa taStats,
                                                       long tiempoMs, int bloqueActual, LocalDate fechaBase,
                                                       int k, int saMin, long saMs,
                                                       int totalEnvios, int totalEnrutadas, int totalSinRuta,
                                                       int totalCumpleSLA, int totalTardadas, long totalMaletas,
                                                       int totalVuelosCancelados,
                                                       boolean colapsoDetectado, int bloqueColapso,
                                                       String motivoColapso, String detalleColapso,
                                                       LocalDateTime instanteColapso) {
        SimulacionResponse res = telemetria.construirRespuestaFront(0, tiempoMs,
                cargadorDatos.getVuelos(), bloqueActual, fechaBase);
        telemetria.llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, totalVuelosCancelados,
                colapsoDetectado, bloqueColapso, motivoColapso, detalleColapso, instanteColapso);
        telemetria.llenarMetricasTa(res.getMetricas(), taStats, saMs);
        telemetria.llenarMetricasBacklog(res.getMetricas(), backlog);
        res.setK(k);
        res.setSaMinutos(saMin);
        if (job != null) job.resultado = res;
        finalizarAuditoriaDiferida(job, auditAcc);
        return res;
    }

    private static boolean cancelacionPedida(EstadoJob job) {
        return job != null && ("cancelado".equals(job.estado) || job.canceladoPorUsuario);
    }

    private static Random rngParaBloque(long seed, String motor, int bloqueIdx) {
        long mixed = seed
                ^ ((long) bloqueIdx * 0x9E3779B97F4A7C15L)
                ^ ((long) (motor != null ? motor.hashCode() : 0) << 32);
        return new Random(mixed);
    }

                        private void logBloque(String motor, int bloque, int total, int envios, int onTime,
                           int tardadas, int sinRuta, long taMs, int backlog, boolean colapso,
                           EstadoJob job, int sinRutaRam) {
        log.info("Bloque {}/{} [{}] | envíos:{} | onTime:{} | tardadas:{} | sinRuta:{} | Ta:{}ms | backlog:{}{}",
                bloque, total, motor, envios, onTime, tardadas, sinRuta, taMs, backlog,
                colapso ? " | COLAPSO" : "");
        if (job != null && (bloque % 50 == 0 || bloque == total)) logHuellaMemoria(job, sinRutaRam);
    }


    private void logHuellaMemoria(EstadoJob job, int sinRutaRam) {
        Runtime rt = Runtime.getRuntime();
        long usadoMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long comprometidoMb = rt.totalMemory() / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        log.info("MEM job={} | heap usado={}MB comprometido={}MB max={}MB ({}%) | bloques={} | "
                        + "vuelosUsadosAcum={} | sinRutaRam={} | jobs={}",
                job.getJobId(), usadoMb, comprometidoMb, maxMb,
                maxMb > 0 ? (usadoMb * 100 / maxMb) : 0,
                job.bloquesPublicados(), job.vuelosUsadosAcumSize(), sinRutaRam, jobs.cantidadJobs());
    }

    private record ResultadoVentana(
            BloqueSimulacion bloque,
            int envios, int enrutadas, int sinRuta, int cumpleSLA, int tardadas, long maletas,
            boolean colapsoAlmacen, String detalleColapso,
            com.tasfb2b.planificador.dto.jobs.AlertaColapso alerta,
            List<OcupacionAlmacenSlot> serieAlmacenes,
            List<LoteEnvio> finalBatches) {
    }

    }
