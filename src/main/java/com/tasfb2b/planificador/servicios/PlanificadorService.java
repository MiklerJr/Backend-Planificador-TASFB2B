package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.aco.*;
import com.tasfb2b.planificador.algoritmo.alns.*;
import com.tasfb2b.planificador.algoritmo.grafo.*;
import com.tasfb2b.planificador.servicios.trabajos.*;
import com.tasfb2b.planificador.servicios.persistencia.*;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.dto.trabajos.AlertaColapso;
import com.tasfb2b.planificador.dto.auditoria.AuditoriaEnvio;
import com.tasfb2b.planificador.dto.vuelos.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.simulacion.EjecucionParametros;
import com.tasfb2b.planificador.dto.almacenes.*;
import com.tasfb2b.planificador.dto.auditoria.*;
import com.tasfb2b.planificador.dto.datos.*;
import com.tasfb2b.planificador.dto.trabajos.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.dto.vuelos.VueloCancelado;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Envio;
import com.tasfb2b.planificador.modelo.datos.TipoEnvio;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.utilidades.validador.ValidadorEnvio;
import com.tasfb2b.planificador.dto.vuelos.VuelosUsadosResponse;
import com.tasfb2b.planificador.utilidades.MapeadorAlgoritmo;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.CalculadorEstadoEnvio;
import com.tasfb2b.planificador.utilidades.analizador.AnalizadorVuelos;
import com.tasfb2b.planificador.utilidades.FormatoSimulacion;
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
    private final CargadorDatos dataLoader;
    private final MapeadorAlgoritmo mapper;
    private final PlanificadorProperties props;
    private final RegistroTrabajos jobs;
    private final AuditoriaService auditoria;
    private final ColoniaACO acoEngine;
    private final PersistenciaSolucionService persistencia;
    private final LectorSolucionBd solucionBdReader;
    private final MotorGrafoCache motorCache;
    private final AlmacenCacheEsqueletos skeletonStore;

    public static final String MOTOR_ALNS = "alns";
    public static final String MOTOR_ACO  = "aco";

    private static final int PREWARM_ROUTE_CANDIDATES = 5;

    private static final int SUFIJO_ROUTE_CANDIDATES = 5;

    private volatile Map<String, Integer> offsetPorCodigo = null;

    private volatile List<BloqueSimulacion> bloquesCacheados = null;

    // ── CONSTRUCTOR UNIFICADO (VITAL PARA SPRING BOOT) ──────────────────
    @org.springframework.beans.factory.annotation.Autowired
    public PlanificadorService(CargadorDatos dataLoader,
                               MapeadorAlgoritmo mapper,
                               PlanificadorProperties props,
                               RegistroTrabajos jobs,
                               AuditoriaService auditoria,
                               ColoniaACO acoEngine,
                               PersistenciaSolucionService persistencia,
                               LectorSolucionBd solucionBdReader,
                               MotorGrafoCache motorCache,
                               AlmacenCacheEsqueletos skeletonStore) {
        this.dataLoader = dataLoader;
        this.mapper = mapper;
        this.props = props;
        this.jobs = jobs;
        this.auditoria = auditoria;
        this.acoEngine = acoEngine;
        this.persistencia = persistencia;
        this.solucionBdReader = solucionBdReader;
        this.motorCache = motorCache;
        this.skeletonStore = skeletonStore;
    }

    PlanificadorService(CargadorDatos dataLoader, MapeadorAlgoritmo mapper, PlanificadorProperties props,
                        RegistroTrabajos jobs, AuditoriaService auditoria,
                        ColoniaACO acoEngine) {
        this(dataLoader, mapper, props, jobs, auditoria, acoEngine,
                new PersistenciaSolucionService(null, null), new LectorSolucionBd(null, null, null),
                new MotorGrafoCache(), new AlmacenCacheEsqueletos(null, null, ""));
    }

    public EstadoTrabajo iniciarEscenario2Async(EjecucionParametros params) {
        if (params == null) params = new EjecucionParametros();
        int k = props.getScenario().getKDefault2();   // K fijo del escenario, no negociable
        String motorRes = resolverMotor(params.getMotor());
        long seedRes = resolverSeed(params.getSeed());

        EstadoTrabajo job = jobs.crear("2", k);
        job.setMaxBloquesConAsignaciones(props.getScenario().getMaxBloquesBuffer());   // anti-OOM (Fase 1)
        job.algoritmo = motorRes;
        job.seed = seedRes;
        job.fechaInicio = params.getFechaInicio();
        // Persistir los overrides para poder reiniciar idéntico (ver reiniciarJob).
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

                skeletonStore.guardarSiCrecio();
            }
        });
        return job;
    }

    public EstadoTrabajo iniciarEscenario3Async(double umbralColapso, String motor, Long seed) {
        return iniciarEscenario3Async(umbralColapso, motor, seed, null);
    }

    public EstadoTrabajo iniciarEscenario3Async(double umbralColapso, String motor, Long seed,
                                           LocalDateTime fechaInicio) {
        int k = props.getScenario().getKDefault3();   // K fijo del escenario, no negociable
        String motorRes = resolverMotor(motor);
        long seedRes = resolverSeed(seed);
        EstadoTrabajo job = jobs.crear("3", k);
        job.setMaxBloquesConAsignaciones(props.getScenario().getMaxBloquesBuffer());   // anti-OOM (Fase 1)
        job.algoritmo = motorRes;
        job.seed = seedRes;
        job.fechaInicio = fechaInicio;
        job.umbralColapso = umbralColapso;   // persistir para reiniciar idéntico (ver reiniciarJob)
        jobs.ejecutar(job, () -> {
            try {
                SimulacionResponse res = ejecutarHastaColapso(k, umbralColapso, job, motorRes, seedRes, fechaInicio);
                job.resultado = res;
            } finally {
                skeletonStore.guardarSiCrecio();   // esqueletos aprendidos durante la corrida
            }
        });
        return job;
    }

    public EstadoTrabajo iniciarEscenario1Async(String motor, Long seed) {
        return iniciarEscenario1Async(motor, seed, null);
    }

    public EstadoTrabajo iniciarEscenario1Async(String motor, Long seed, LocalDateTime fechaInicio) {
        return iniciarEscenario1Async(motor, seed, fechaInicio, false);
    }

    public EstadoTrabajo iniciarEscenario1Async(String motor, Long seed, LocalDateTime fechaInicio,
                                           boolean enVivo) {
        String motorRes = resolverMotor(motor);
        long seedRes = resolverSeed(seed);
        int k = props.getScenario().getKDefault1();
        EstadoTrabajo job = jobs.crear("1", k);
        job.setMaxBloquesConAsignaciones(props.getScenario().getMaxBloquesBuffer());   // anti-OOM (Fase 1)
        job.algoritmo = motorRes;
        job.seed = seedRes;
        // En operación EN VIVO el cursor es now() UTC: fechaInicio no aplica (se ignora).
        job.fechaInicio = enVivo ? null : fechaInicio;
        job.enVivo = enVivo;
        final LocalDateTime fechaEff = job.fechaInicio;
        jobs.ejecutar(job, () -> {
            try {
                SimulacionResponse res = ejecutarEscenario1(job, motorRes, seedRes, fechaEff, enVivo);
                job.resultado = res;
            } finally {
                skeletonStore.guardarSiCrecio();   // esqueletos aprendidos durante la corrida
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

    public EstadoTrabajo getJob(String jobId) {
        return jobs.get(jobId);
    }

    public boolean cancelarJob(String jobId) {
        return jobs.cancelar(jobId);
    }

    public EstadoTrabajo reiniciarJob(String jobId) {
        EstadoTrabajo viejo = getJob(jobId);
        if (viejo == null) return null;
        if (RegistroTrabajos.ESTADOS_ACTIVOS.contains(viejo.estado)) {
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

    public List<EstadoTrabajo> listarJobsActivos() {
        return jobs.listarActivos();
    }

    public List<EstadoTrabajo> listarTodosLosJobs() {
        return jobs.listarTodos();
    }

    public ListaTrabajosResponse listarJobsResponse(boolean activos) {
        List<EstadoTrabajo> lista = activos ? listarJobsActivos() : listarTodosLosJobs();
        List<ListaTrabajosResponse.ResumenTrabajo> items = new ArrayList<>(lista.size());
        for (EstadoTrabajo j : lista) {
            ListaTrabajosResponse.ResumenTrabajo item = new ListaTrabajosResponse.ResumenTrabajo();
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
        ListaTrabajosResponse body = new ListaTrabajosResponse();
        body.setJobs(items);
        body.setTotal(items.size());
        return body;
    }

    public int posicionEnCola(String jobId) {
        return jobs.posicionEnCola(jobId);
    }

    public boolean solicitarCancelacionVuelo(String jobId, CancelacionVueloRequest orden) {
        if (orden == null) return false;
        EstadoTrabajo job = jobs.get(jobId);
        if (job == null) return false;
        if (!RegistroTrabajos.ESTADOS_ACTIVOS.contains(job.estado)) return false;
        if (job.encolarCancelacionVuelo(orden)) {
            log.info("Cancelación de vuelo encolada (job {}): {}->{} salida {}", jobId,
                    orden.getOrigen(), orden.getDestino(), orden.getFechaHoraSalida());
        } else {
            log.debug("Cancelación de vuelo duplicada ya pendiente (job {}): {}->{} salida {} — ignorada",
                    jobId, orden.getOrigen(), orden.getDestino(), orden.getFechaHoraSalida());
        }
        return true;   // aceptada para el front (encolada o ya pendiente)
    }

    public int solicitarInyeccionEnvios(String jobId, InyeccionEnviosRequest req) {
        if (req == null || req.getEnvios() == null || req.getEnvios().isEmpty())
            throw new ParametroInvalidoException("inyección vacía: se requiere al menos un envío");
        EstadoTrabajo job = jobs.get(jobId);
        if (job == null) return -1;
        for (InyeccionEnviosRequest.Item it : req.getEnvios()) {
            if (!ValidadorEnvio.camposObligatoriosPresentes(it.getOrigen(), it.getDestino()))
                throw new ParametroInvalidoException("origen y destino son obligatorios (RF03)");
            if (ValidadorEnvio.esMismoAeropuerto(it.getOrigen(), it.getDestino()))
                throw new ParametroInvalidoException("origen y destino no pueden ser iguales (RF02)");
            if (it.getCantidad() <= 0)
                throw new ParametroInvalidoException("la cantidad debe ser > 0");
            if (dataLoader.getAeropuerto(it.getOrigen()) == null)
                throw new ParametroInvalidoException("ICAO origen desconocido: " + it.getOrigen());
            if (dataLoader.getAeropuerto(it.getDestino()) == null)
                throw new ParametroInvalidoException("ICAO destino desconocido: " + it.getDestino());
        }
        if (!RegistroTrabajos.ESTADOS_ACTIVOS.contains(job.estado)) return -1;
        for (InyeccionEnviosRequest.Item it : req.getEnvios()) job.encolarInyeccion(it);
        log.info("Inyección de {} envío(s) encolada (job {})", req.getEnvios().size(), jobId);
        return req.getEnvios().size();
    }

    private int aplicarCancelacionesVuelo(String jobId, java.util.Queue<CancelacionVueloRequest> cola,
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
            long epochMin = epochDay * CodificadorClaveVuelo.DAY_MIN;

            List<Arista> matches = new ArrayList<>();
            for (Arista e : graph.edges) {
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
                if (noAplicadas != null) noAplicadas.add(orden);
                continue;
            }

            List<Arista> edgesCancelados = new ArrayList<>();
            for (Arista e : matches) {
                if (enrutador.addCancelledFlight(CodificadorClaveVuelo.flightKey(e.idx, epochMin))) {
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

    private int aplicarInyeccionesEnvio(EstadoTrabajo job, List<InyeccionEnviosRequest.Item> buffer,
                                        ContextoTemporal ctx, GestorBacklog backlog) {
        if (job == null || backlog == null) return 0;
        InyeccionEnviosRequest.Item it;
        while ((it = job.getInyeccionesPendientes().poll()) != null) buffer.add(it);
        if (buffer.isEmpty()) return 0;
        List<EnvioInyectadoInfo> liberados = new ArrayList<>();
        Iterator<InyeccionEnviosRequest.Item> itr = buffer.iterator();
        int n = 0;
        while (itr.hasNext()) {
            InyeccionEnviosRequest.Item x = itr.next();
            LocalDateTime ready = x.getFechaHoraRegistro();              // UTC o null
            if (ready != null && !ready.isBefore(ctx.scEnd)) continue;   // aún futura → esperar
            LocalDateTime readyEff = (ready != null) ? ready : ctx.scStart;  // honra el pasado para el SLA
            Aeropuerto o = dataLoader.getAeropuerto(x.getOrigen());
            Aeropuerto d = dataLoader.getAeropuerto(x.getDestino());
            if (o == null || d == null) { itr.remove(); continue; }      // defensivo (ya validado al encolar)
            int sla = TipoEnvio.derivar(o, d) == TipoEnvio.INTRACONTINENTAL ? 24 : 48;
            String id = "INV-" + ctx.bloqueIdx + "-" + (n++);            // sintético; NO existe en ENVIO
            LoteEnvio b = new LoteEnvio(id, x.getCantidad(), sla,
                    o.getCodigo(), d.getCodigo(), readyEff);
            b.setSintetico(true);
            if (x.getClienteId() != null) b.setClienteId(x.getClienteId());
            backlog.addSinRuta(b);                                       // el flujo estándar lo recoge
            EnvioInyectadoInfo info = new EnvioInyectadoInfo(id, o.getCodigo(), d.getCodigo(),
                    x.getCantidad(), x.getClienteId(), sla, readyEff.toString(), ctx.bloqueIdx,
                    x.getRegistrador(), x.getSede());
            job.getEnviosInyectados().add(info);                         // siempre (para /estado, en RAM)
            liberados.add(info);
            itr.remove();
        }
        if (!liberados.isEmpty()) {
            persistencia.persistirInyecciones(job.getJobId(), liberados);   // BD activa, best-effort
            log.info("Inyección: {} envío(s) liberado(s) al bloque {} (job {})",
                    liberados.size(), ctx.bloqueIdx, job.getJobId());
        }
        return liberados.size();
    }

    private int reencolarAfectadosPorCancelacion(List<Arista> edgesCancelados, long epochDay,
                                                 GestorBacklog backlog,
                                                 Map<String, Arista> indiceVuelo) {
        if (backlog == null || edgesCancelados == null || edgesCancelados.isEmpty()) return 0;
        List<String> idsVuelo = new ArrayList<>(edgesCancelados.size());
        for (Arista e : edgesCancelados) idsVuelo.add(PersistenciaSolucionService.normalizarIdVuelo(e.id));

        List<LoteEnvio> afectados = solucionBdReader.afectadosPorVuelo(
                idsVuelo, java.time.LocalDate.ofEpochDay(epochDay), indiceVuelo);
        for (LoteEnvio b : afectados) backlog.addReplanificable(b);
        return afectados.size();
    }

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

    public SerieAlmacenesResponse getSerieAlmacenes(String jobId, int desde) {
        EstadoTrabajo job = getJob(jobId);
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

    public EstadoInicialResponse buildEstadoInicialResponse(EstadoTrabajo job) {
        EstadoInicialResponse body = new EstadoInicialResponse();
        body.setJobId(job.getJobId());
        if (job.fechaInicio != null) body.setFechaInicio(job.fechaInicio.toString());
        List<AsignacionMaleta> snapshot = job.estadoInicial;
        body.setTotal(snapshot.size());
        body.setAsignaciones(snapshot);
        return body;
    }

    public EstadoTrabajoResponse getEstadoJob(String jobId) {
        EstadoTrabajo job = getJob(jobId);
        if (job == null) return null;

        EstadoTrabajoResponse body = new EstadoTrabajoResponse();
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
        body.setBloqueWarmup(job.bloqueWarmup);
        body.setTotalBloquesWarmup(job.totalBloquesWarmup);
        body.setProgresoWarmup(job.getProgresoWarmup());
        body.setPosicionEnCola(posicionEnCola(jobId));
        body.setCanceladoPorUsuario(job.canceladoPorUsuario);
        body.setTaPromedioMs(job.taPromedioMs);
        body.setInicio(job.inicio.toString());
        if (job.fin != null) body.setFin(job.fin.toString());
        if (job.error != null) body.setError(job.error);
        if (job.alertaColapso != null) body.setAlertaColapso(job.alertaColapso);
        body.setVuelosCancelados(job.getVuelosCancelados());
        body.setCancelacionesNoAplicadas(job.getCancelacionesNoAplicadas());
        body.setEnviosInyectados(job.getEnviosInyectados());
        return body;
    }

    public SimulacionResponse ejecutarALNS(int k) {
        return ejecutarALNS(k, null, MOTOR_ALNS, resolverSeed(null), null);
    }

    public SimulacionResponse ejecutarALNS(int k, EstadoTrabajo job) {
        return ejecutarALNS(k, job, MOTOR_ALNS, resolverSeed(null), null);
    }

    public SimulacionResponse ejecutarALNS(int k, EstadoTrabajo job, String motor) {
        return ejecutarALNS(k, job, motor, resolverSeed(null), null);
    }

    public SimulacionResponse ejecutarALNS(int k, EstadoTrabajo job, String motor, long seed) {
        return ejecutarALNS(k, job, motor, seed, null);
    }

    public SimulacionResponse ejecutarALNS(int k, EstadoTrabajo job, String motor,
                                            long seed, LocalDateTime fechaInicio) {
        EjecucionParametros p = new EjecucionParametros();
        p.setK(k);
        p.setMotor(motor);
        p.setSeed(seed);
        p.setFechaInicio(fechaInicio);
        return ejecutarALNS(p, job);
    }

    public SimulacionResponse ejecutarALNS(EjecucionParametros params, EstadoTrabajo job) {
        if (params == null) params = new EjecucionParametros();
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

        List<ContextoTemporal> plan = construirPlanBloques(k, fechaInicio, saOverride, diasOverride);
        if (plan.isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k);
            r.setSaMinutos(saMin);
            return r;
        }

        List<ContextoTemporal> warmupPlan = params.isProcesamientoPrevio()
                ? construirPlanWarmup(k, fechaInicio, saOverride)
                : Collections.emptyList();

        Grafo graph = motorCache.obtenerGrafo(
                () -> mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos()));
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph, motorCache.skeletonCache());
        enrutador.configurarStorageAware(props.getStorageAware().getUmbralHubPico(),
                props.getStorageAware().getPrecioHubExponente());   // Fase P
        SolucionAlns solucionDummy = new SolucionAlns(Collections.emptyList());

        int totalBloques = plan.size();
        int intervaloReporte = Math.max(1, totalBloques / 10);

        int totalVuelosCancelados = 0;
        List<VueloCancelado> vuelosCancelados = job != null ? job.getVuelosCancelados() : new ArrayList<>();
        List<CancelacionVueloRequest> cancelacionesNoAplicadas =
                job != null ? job.getCancelacionesNoAplicadas() : new ArrayList<>();

        List<BloqueSimulacion> bloques = new ArrayList<>(totalBloques);
        Map<String, int[]> odStats = new HashMap<>();
        int totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
                totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        EstadisticasTa taStats = new EstadisticasTa();
        boolean simularTiempoReal = props.getScenario().isSimularTiempoReal2();
        long saMs = saMin * 60_000L;
        GestorBacklog backlog = crearBacklogConPurga(enrutador);
        AcumuladorAuditoria auditAcc = new AcumuladorAuditoria(false);
        AcumuladorAuditoria auditWarmup = ejecutarWarmup(warmupPlan, job, graph, enrutador,
                solucionDummy, odStats, backlog, motorRes, seed, taFijoMs, fechaInicio);
        if (job != null) job.estadoInicial = construirEstadoInicial(auditWarmup.completos());

        if (props.getScenario().isPrewarmSkeletons() && !plan.isEmpty()) {
            long t0Prewarm = System.currentTimeMillis();
            List<Envio> demandaVentana = dataLoader.getMaletasEnRango(
                    plan.get(0).scStart, plan.get(plan.size() - 1).scEnd);
            if (job != null && !cancelacionPedida(job)) job.estado = "calentando";
            log.info("Pre-warm iniciado: {} envíos en ventana | caché de esqueletos con {} claves precargadas",
                    demandaVentana.size(), motorCache.skeletonCache().size());
            int clavesCalentadas = enrutador.precalentarEsqueletos(
                    mapper.mapToBatches(demandaVentana), PREWARM_ROUTE_CANDIDATES,
                    () -> cancelacionPedida(job));
            log.info("Pre-warm esqueletos (N3): {} claves desde {} envíos en {} ms",
                    clavesCalentadas, demandaVentana.size(), System.currentTimeMillis() - t0Prewarm);
            if (job != null && !cancelacionPedida(job)) job.estado = "ejecutando";
            skeletonStore.guardarSiCrecio();
        }

        if (cancelacionPedida(job)) {
            log.info("E2 cancelado por usuario antes del primer bloque (warm-up/pre-warm)");
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k);
            r.setSaMinutos(saMin);
            return r;
        }

        persistencia.iniciarCorrida(job != null ? job.getJobId() : null);

        boolean colapsoAlmacenDetectado = false;   // E2 se detiene ante colapso de almacén.
        int bloqueColapsoAlmacen = -1;
        String detalleColapsoE2 = null;            // detalle (qué/dónde) e instante UTC del colapso.
        LocalDateTime instanteColapsoE2 = null;
        String nivelAlertaPrevio = AlertaColapso.VERDE;
        for (ContextoTemporal ctx : plan) {
            bloqueActual++;
            totalVuelosCancelados += aplicarCancelacionesVuelo(
                    job != null ? job.getJobId() : null,
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, vuelosCancelados, cancelacionesNoAplicadas);
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motorRes, rngBloque, taFijoMs);
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

            if (job != null) {
                job.bloqueActual = bloqueActual;
                job.totalBloques = totalBloques;
                job.taPromedioMs = taStats.promedio();
                job.registrarVentanaSimulada(ctx.scStart, ctx.scEnd);   // ventana realmente simulada
                job.publicarBloque(rv.bloque);
                job.publicarSerieAlmacenes(rv.serieAlmacenes());
                job.metricasSnapshot = metricasSnapshotDe(totales, taStats.promedio());
                job.alertaColapso = rv.alerta();
                persistencia.persistirBloque(job.getJobId(), rv.finalBatches());
                if ("cancelado".equals(job.estado) || job.canceladoPorUsuario) {
                    log.info("E2 cancelado por usuario en bloque {}/{}", bloqueActual, totalBloques);
                    break;
                }
            }
            nivelAlertaPrevio = avisarColapsoInminente("E2", rv.alerta(), bloqueActual, nivelAlertaPrevio);

            if (bloqueActual < plan.size()) {
                double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
                plan.get(bloqueActual).tasaSinRutaPrevia = tasa;
            }

            backlog.purgarVencidas(ctx.scEnd);

            logBloque(motorRes, bloqueActual, totalBloques,
                    rv.envios, rv.cumpleSLA, rv.tardadas, rv.sinRuta, ctx.taMs, backlog.size(), rv.colapsoAlmacen(), job,
                    auditAcc.sinRutaSize());

            if (rv.colapsoAlmacen()) {
                colapsoAlmacenDetectado = true;
                bloqueColapsoAlmacen = bloqueActual;
                detalleColapsoE2 = rv.detalleColapso();
                instanteColapsoE2 = ctx.scEnd;
                log.warn("E2 COLAPSO por almacén lleno en bloque {}/{} — {}",
                        bloqueActual, totalBloques, rv.detalleColapso());
                break;
            }

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
                tiempoMs, bloqueActual, totalEnvios, totalMaletas,
                totalCumpleSLA, totalTardadas, totalSinRuta,
                taStats.min(), taStats.promedio(), taStats.max(), saMs,
                backlog.picoHistorico(), backlog.size(), backlog.sinRutaDefinitivo());
        if (colapsoAlmacenDetectado) {
            log.warn("E2 detenido por COLAPSO de almacén en bloque {}", bloqueColapsoAlmacen);
        }
        logDiagnosticos(odStats, graph, enrutador);

        SimulacionResponse res = construirRespuestaFront(0, tiempoMs,
                dataLoader.getVuelos(), bloqueActual, plan.get(0).scStart.toLocalDate());
        llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, totalVuelosCancelados,
                colapsoAlmacenDetectado, bloqueColapsoAlmacen,
                colapsoAlmacenDetectado ? "almacen_lleno" : null, detalleColapsoE2, instanteColapsoE2);
        llenarMetricasTa(res.getMetricas(), taStats, saMs);
        llenarMetricasBacklog(res.getMetricas(), backlog);
        res.setK(k);
        res.setSaMinutos(saMin);

        if (job != null) job.resultado = res;
        finalizarAuditoriaDiferida(job, auditAcc);
        return res;
    }

    public BloqueSimulacion getBloque(int index) {
        if (bloquesCacheados == null || index < 0 || index >= bloquesCacheados.size()) return null;
        return bloquesCacheados.get(index);
    }

    public SimulacionResponse ejecutarEscenario1(EstadoTrabajo job, String motor, long seed) {
        return ejecutarEscenario1(job, motor, seed, null, false);
    }

    public SimulacionResponse ejecutarEscenario1(EstadoTrabajo job, String motor, long seed,
                                                 LocalDateTime fechaInicio) {
        return ejecutarEscenario1(job, motor, seed, fechaInicio, false);
    }


    public SimulacionResponse ejecutarEscenario1(EstadoTrabajo job, String motor, long seed,
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
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k);
            r.setSaMinutos(saMin);
            return r;
        }

        Grafo graph = motorCache.obtenerGrafo(
                () -> mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos()));
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph, motorCache.skeletonCache());
        enrutador.configurarStorageAware(props.getStorageAware().getUmbralHubPico(),
                props.getStorageAware().getPrecioHubExponente());   // Fase P
        SolucionAlns solucionDummy = new SolucionAlns(Collections.emptyList());


        int totalVuelosCancelados = 0;
        List<VueloCancelado> vuelosCancelados = job != null ? job.getVuelosCancelados() : new ArrayList<>();
        List<CancelacionVueloRequest> cancelacionesNoAplicadas =
                job != null ? job.getCancelacionesNoAplicadas() : new ArrayList<>();
        List<InyeccionEnviosRequest.Item> bufferInyecciones = new ArrayList<>();

        List<BloqueSimulacion> bloques = new ArrayList<>(plan.size());
        Map<String, int[]> odStats = new HashMap<>();
        int totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
                totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        EstadisticasTa taStats = new EstadisticasTa();
        boolean simularTiempoReal = enVivo || props.getScenario().isSimularTiempoReal1();
        long saMs = saMin * 60_000L;
        int totalBloques = plan.size();
        GestorBacklog backlog = crearBacklogConPurga(enrutador);
        AcumuladorAuditoria auditAcc = new AcumuladorAuditoria(false);
        int intervaloReporte = Math.max(1, totalBloques / 10);
        persistencia.iniciarCorrida(job != null ? job.getJobId() : null);

        boolean colapsoAlmacenDetectado = false;   // E1 se detiene ante colapso de almacén.
        int bloqueColapsoAlmacen = -1;
        String detalleColapsoE1 = null;            // detalle (qué/dónde) e instante UTC del colapso.
        LocalDateTime instanteColapsoE1 = null;
        String nivelAlertaPrevio = AlertaColapso.VERDE;

        AcumuladorAuditoria auditWarmup = ejecutarWarmup(warmupPlan, job, graph, enrutador,
                solucionDummy, odStats, backlog, motorRes, seed, taFijoMs, fechaInicio);
        if (job != null) job.estadoInicial = construirEstadoInicial(auditWarmup.completos());

        for (ContextoTemporal ctx : plan) {
            bloqueActual++;
            totalVuelosCancelados += aplicarCancelacionesVuelo(
                    job != null ? job.getJobId() : null,
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, vuelosCancelados, cancelacionesNoAplicadas);
            if (job != null) aplicarInyeccionesEnvio(job, bufferInyecciones, ctx, backlog);
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motorRes, rngBloque, taFijoMs, false, enVivo);

            rv.bloque.setTiempoProcesamientoMs(ctx.taMs);

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

            if (job != null) {
                job.bloqueActual = bloqueActual;
                job.totalBloques = totalBloques;
                job.taPromedioMs = taStats.promedio();
                job.registrarVentanaSimulada(ctx.scStart, ctx.scEnd);   // ventana realmente simulada
                job.publicarBloque(rv.bloque);
                job.publicarSerieAlmacenes(rv.serieAlmacenes());
                job.metricasSnapshot = metricasSnapshotDe(totales, taStats.promedio());
                job.alertaColapso = rv.alerta();
                persistencia.persistirBloque(job.getJobId(), rv.finalBatches());
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

            backlog.purgarVencidas(ctx.scEnd);

            logBloque(motorRes, bloqueActual, totalBloques,
                    rv.envios, rv.cumpleSLA, rv.tardadas, rv.sinRuta, ctx.taMs, backlog.size(), rv.colapsoAlmacen(), job,
                    auditAcc.sinRutaSize());

            if (rv.colapsoAlmacen()) {
                colapsoAlmacenDetectado = true;
                bloqueColapsoAlmacen = bloqueActual;
                detalleColapsoE1 = rv.detalleColapso();
                instanteColapsoE1 = ctx.scEnd;
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
                tiempoMs, bloqueActual, totalEnvios, totalMaletas,
                totalCumpleSLA, totalTardadas, totalSinRuta,
                taStats.min(), taStats.promedio(), taStats.max(), saMs,
                backlog.picoHistorico(), backlog.size(), backlog.sinRutaDefinitivo());
        if (colapsoAlmacenDetectado) {
            log.warn("E1 detenido por COLAPSO de almacén en bloque {}", bloqueColapsoAlmacen);
        }
        logDiagnosticos(odStats, graph, enrutador);

        SimulacionResponse res = construirRespuestaFront(0, tiempoMs,
                dataLoader.getVuelos(), bloqueActual, plan.get(0).scStart.toLocalDate());
        llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, totalVuelosCancelados,
                colapsoAlmacenDetectado, bloqueColapsoAlmacen,
                colapsoAlmacenDetectado ? "almacen_lleno" : null, detalleColapsoE1, instanteColapsoE1);
        llenarMetricasTa(res.getMetricas(), taStats, saMs);
        llenarMetricasBacklog(res.getMetricas(), backlog);
        res.setK(k);
        res.setSaMinutos(saMin);

        if (job != null) job.resultado = res;
        finalizarAuditoriaDiferida(job, auditAcc);
        return res;
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso) {
        return ejecutarHastaColapso(k, umbralColapso, null, MOTOR_ALNS, resolverSeed(null));
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso, EstadoTrabajo job) {
        return ejecutarHastaColapso(k, umbralColapso, job, MOTOR_ALNS, resolverSeed(null));
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso, EstadoTrabajo job, String motor) {
        return ejecutarHastaColapso(k, umbralColapso, job, motor, resolverSeed(null));
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso,
                                                   EstadoTrabajo job, String motor, long seed) {
        return ejecutarHastaColapso(k, umbralColapso, job, motor, seed, null);
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double umbralColapso,
                                                   EstadoTrabajo job, String motor, long seed,
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

        List<ContextoTemporal> plan = construirPlanBloquesHastaColapso(k, fechaInicio);
        List<ContextoTemporal> warmupPlan = fechaInicio != null
                ? construirPlanWarmup(k, fechaInicio, null)
                : Collections.emptyList();
        if (plan.isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k);
            r.setSaMinutos(saMin);
            return r;
        }

        Grafo graph = motorCache.obtenerGrafo(
                () -> mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos()));
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph, motorCache.skeletonCache());
        enrutador.configurarStorageAware(props.getStorageAware().getUmbralHubPico(),
                props.getStorageAware().getPrecioHubExponente());   // Fase P
        SolucionAlns solucionDummy = new SolucionAlns(Collections.emptyList());

        List<VueloCancelado> vuelosCancelados = job != null ? job.getVuelosCancelados() : new ArrayList<>();
        List<CancelacionVueloRequest> cancelacionesNoAplicadas =
                job != null ? job.getCancelacionesNoAplicadas() : new ArrayList<>();

        List<BloqueSimulacion> bloques = new ArrayList<>();
        Map<String, int[]> odStats = new HashMap<>();
        int totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
                totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        boolean collapsoDetectado = false;
        int bloqueColapso = -1;
        String detalleColapsoE3 = null;            // detalle (qué/dónde) e instante UTC del colapso.
        LocalDateTime instanteColapsoE3 = null;
        String motivoParada = "falta_datos";
        String nivelAlertaPrevio = AlertaColapso.VERDE;
        EstadisticasTa taStats = new EstadisticasTa();
        boolean simularTiempoReal = props.getScenario().isSimularTiempoReal3();
        long saMs = saMin * 60_000L;
        int totalBloques = plan.size();
        GestorBacklog backlog = crearBacklogConPurga(enrutador);
        AcumuladorAuditoria auditAcc = new AcumuladorAuditoria(false);

        AcumuladorAuditoria auditWarmup = ejecutarWarmup(warmupPlan, job, graph, enrutador,
                solucionDummy, odStats, backlog, motorRes, seed,
                props.getScenario().getTaSegundos() * 1000L, fechaInicio);
        if (job != null) job.estadoInicial = construirEstadoInicial(auditWarmup.completos());

        persistencia.iniciarCorrida(job != null ? job.getJobId() : null);

        for (ContextoTemporal ctx : plan) {
            bloqueActual++;
            aplicarCancelacionesVuelo(
                    job != null ? job.getJobId() : null,
                    job != null ? job.getCancelacionesVueloPendientes() : null,
                    graph, enrutador, backlog, vuelosCancelados, cancelacionesNoAplicadas);
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motorRes, rngBloque);

            rv.bloque.setTiempoProcesamientoMs(ctx.taMs);

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

            if (job != null) {
                job.bloqueActual = bloqueActual;
                job.totalBloques = totalBloques;
                job.taPromedioMs = taStats.promedio();
                job.registrarVentanaSimulada(ctx.scStart, ctx.scEnd);   // ventana realmente simulada
                // Publicación incremental para dibujo en tiempo real desde el front.
                job.publicarBloque(rv.bloque);
                job.publicarSerieAlmacenes(rv.serieAlmacenes());
                job.metricasSnapshot = metricasSnapshotDe(totales, taStats.promedio());
                job.alertaColapso = rv.alerta();
                persistencia.persistirBloque(job.getJobId(), rv.finalBatches());
                if ("cancelado".equals(job.estado) || job.canceladoPorUsuario) {
                    motivoParada = "cancelado_front";
                    log.info("E3 cancelado por usuario en bloque {}/{}", bloqueActual, totalBloques);
                    break;
                }
            }
            nivelAlertaPrevio = avisarColapsoInminente("E3", rv.alerta(), bloqueActual, nivelAlertaPrevio);

            if (bloqueActual < plan.size()) {
                double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
                plan.get(bloqueActual).tasaSinRutaPrevia = tasa;
            }

            int vencidos = backlog.purgarVencidas(ctx.scEnd);
            boolean backlogDefinitivo = vencidos > 0;

            logBloque(motorRes, bloqueActual, totalBloques,
                    rv.envios, rv.cumpleSLA, rv.tardadas, rv.sinRuta, ctx.taMs, backlog.size(),
                    backlogDefinitivo || rv.colapsoAlmacen(), job, auditAcc.sinRutaSize());

            if (rv.colapsoAlmacen()) {
                collapsoDetectado = true;
                bloqueColapso = bloqueActual;
                motivoParada = "almacen_lleno";
                detalleColapsoE3 = rv.detalleColapso();
                instanteColapsoE3 = ctx.scEnd;
                log.warn("E3 ALMACÉN LLENO en bloque {}/{} — envío {}", bloqueActual, totalBloques, rv.detalleColapso());
                break;
            }

            if (backlogDefinitivo) {
                collapsoDetectado = true;
                bloqueColapso = bloqueActual;
                motivoParada = "backlog_definitivo";
                detalleColapsoE3 = vencidos + " envío(s) del backlog con SLA vencido";
                instanteColapsoE3 = ctx.scEnd;
                break;
            }

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
                bloqueActual, totalEnvios, totalMaletas,
                totalCumpleSLA, totalTardadas, totalSinRuta,
                taStats.min(), taStats.promedio(), taStats.max(),
                backlog.picoHistorico(), backlog.size(), backlog.sinRutaDefinitivo(), tiempoMs);

        SimulacionResponse res = construirRespuestaFront(0, tiempoMs,
                dataLoader.getVuelos(), bloqueActual, plan.get(0).scStart.toLocalDate());
        llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, 0, collapsoDetectado, bloqueColapso,
                collapsoDetectado ? motivoParada : null, detalleColapsoE3, instanteColapsoE3);
        llenarMetricasTa(res.getMetricas(), taStats, saMs);
        llenarMetricasBacklog(res.getMetricas(), backlog);
        res.setK(k);
        res.setSaMinutos(saMin);

        if (job != null) job.resultado = res;
        finalizarAuditoriaDiferida(job, auditAcc);
        return res;
    }

    private void finalizarAuditoriaDiferida(EstadoTrabajo job, AcumuladorAuditoria auditAcc) {
        String jobId = job != null ? job.getJobId() : null;
        try {
            if (job != null && auditAcc != null) {
                // Sin-ruta: no llegan a BD (Fase 5a solo persiste enrutados) ⇒ se retienen en RAM
                // (ligeros, sin Edges) para incluirlos en el ZIP cuando se solicite.
                job.auditoriaSinRuta = new ArrayList<>(auditAcc.sinRuta());
            }
        } finally {
            // Libera el lock de persistencia. NO se toca corridaPersistidaEnBd: la solución sigue en BD
            // para la auditoría on-demand hasta que otra corrida haga TRUNCATE.
            persistencia.finalizarCorrida(jobId);
        }
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

    private RangoAuditoria resolverRangoAuditoria(EstadoTrabajo job, LocalDateTime desde, LocalDateTime hasta) {
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
        EstadoTrabajo job = jobs.get(jobId);
        if (job == null) return ResultadoAuditoria.error("job inexistente");
        if (auditoria == null) return ResultadoAuditoria.error("auditoría no disponible (sin servicio de auditoría)");
        if (RegistroTrabajos.ESTADOS_ACTIVOS.contains(job.estado)) {
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
                    () -> mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos()));
            Map<String, Arista> indiceVuelo = solucionBdReader.construirIndiceVuelo(graph);
            List<VueloCancelado> cancelaciones =
                    solucionBdReader.leerCancelaciones(indiceVuelo, rango.desde(), rango.hasta());
            List<LoteEnvio> sinRuta = filtrarSinRutaPorRango(job.auditoriaSinRuta, rango.desde(), rango.hasta());
            java.util.function.Consumer<java.util.function.Consumer<LoteEnvio>> fuenteEnrutados =
                    sink -> solucionBdReader.forEachEnrutado(indiceVuelo, rango.desde(), rango.hasta(), sink);

            Thread.interrupted();   // defensivo: el hilo HTTP no debería traer el flag, pero el ZIP usa NIO
            job.borrarZip();        // descarta el ZIP anterior de este job (regeneración) antes del nuevo
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
            // Libera el lock de lectura (no toca corridaPersistidaEnBd ⇒ se puede volver a pedir).
            persistencia.finalizarCorrida(jobId);
        }
    }

    public ResultadoEstimacion estimarAuditoria(String jobId, LocalDateTime desde, LocalDateTime hasta) {
        EstadoTrabajo job = jobs.get(jobId);
        if (job == null) return ResultadoEstimacion.error("job inexistente");
        if (RegistroTrabajos.ESTADOS_ACTIVOS.contains(job.estado)) {
            return ResultadoEstimacion.error("el job aún está activo; la auditoría estará disponible al terminar");
        }
        if (!persistencia.reflejaEnBd(jobId)) {
            return ResultadoEstimacion.error(
                    "la solución de este job ya fue reemplazada por una corrida posterior; auditoría no disponible");
        }
        RangoAuditoria rango = resolverRangoAuditoria(job, desde, hasta);   // 400 si el rango es inválido
        long enrutados = solucionBdReader.contarEnrutados(rango.desde(), rango.hasta());
        long sinRuta = filtrarSinRutaPorRango(job.auditoriaSinRuta, rango.desde(), rango.hasta()).size();
        long filasEnvios = enrutados + sinRuta;
        long cancelaciones = solucionBdReader.contarCancelaciones(rango.desde(), rango.hasta());
        int filasPorArchivo = AuditoriaService.FILAS_POR_ARCHIVO;
        int csvEnvios = (int) Math.ceil(filasEnvios / (double) filasPorArchivo);
        int csvCancelaciones = 1;   // el CSV de cancelaciones siempre se emite, aun vacío
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
            LocalDateTime ready = b.getReadyTime();
            if (ready == null) continue;
            if (desde != null && ready.isBefore(desde)) continue;
            if (hasta != null && !ready.isBefore(hasta)) continue;   // hasta exclusivo
            out.add(b);
        }
        return out;
    }

    private ResultadoVentana procesarBloque(ContextoTemporal ctx,
                                            Grafo graph,
                                            OperadorReparacionVoraz enrutador,
                                            SolucionAlns solucionDummy,
                                            Map<String, int[]> odStats,
                                            GestorBacklog backlog) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, null, MOTOR_ALNS, null);
    }

    private ResultadoVentana procesarBloque(ContextoTemporal ctx,
                                            Grafo graph,
                                            OperadorReparacionVoraz enrutador,
                                            SolucionAlns solucionDummy,
                                            Map<String, int[]> odStats,
                                            GestorBacklog backlog,
                                            AcumuladorAuditoria auditAcc) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, MOTOR_ALNS, null);
    }

    private ResultadoVentana procesarBloque(ContextoTemporal ctx,
                                            Grafo graph,
                                            OperadorReparacionVoraz enrutador,
                                            SolucionAlns solucionDummy,
                                            Map<String, int[]> odStats,
                                            GestorBacklog backlog,
                                            AcumuladorAuditoria auditAcc,
                                            String motor) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motor, null, 0L);
    }

    private ResultadoVentana procesarBloque(ContextoTemporal ctx,
                                            Grafo graph,
                                            OperadorReparacionVoraz enrutador,
                                            SolucionAlns solucionDummy,
                                            Map<String, int[]> odStats,
                                            GestorBacklog backlog,
                                            AcumuladorAuditoria auditAcc,
                                            String motor,
                                            Random rngSim) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog, auditAcc, motor, rngSim, 0L);
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
                                            long taFijoMsOverride) {
        return procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog,
                auditAcc, motor, rngSim, taFijoMsOverride, false, false);
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
                : dataLoader.getMaletasEnRango(ctx.scStart, ctx.scEnd);
        List<LoteEnvio> bloqueBatches = mapper.mapToBatches(maletasVentana);

        List<LoteEnvio> afectadosCrudos = new ArrayList<>();
        if (backlog != null) {
            List<LoteEnvio> pendientes = backlog.pollPendientesUrgentes(
                    props.getBacklog().getMaxReprocesoPorBloque());

            List<LoteEnvio> normales = new ArrayList<>();
            for (LoteEnvio b : pendientes) {
                enrutador.removerEsperaOrigenBacklog(b);
                if (b.tienePrefijo() || enrutador.rutaUsaVueloCancelado(b)) {
                    afectadosCrudos.add(b);   // carril Fase 2 (NO entra al motor)
                } else {
                    if (b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty()) {
                        enrutador.releaseFromGlobal(b);
                        b.clearRoute();
                    }
                    normales.add(b);
                }
            }
            if (!normales.isEmpty()) {
                bloqueBatches = new ArrayList<>(bloqueBatches.size() + normales.size());
                bloqueBatches.addAll(normales);
                bloqueBatches.addAll(mapper.mapToBatches(maletasVentana));
            }
        }

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        List<LoteEnvio> afectadosResueltos = new ArrayList<>();
        for (LoteEnvio b : afectadosCrudos) {
            afectadosResueltos.add(
                    reenrutarAfectadoDesdePosicion(b, ctx, graph, enrutador, blockFlight, blockAirport));
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
            enrutador.commitBlock(blockFlight, blockAirport);
        } else {
            List<LoteEnvio> intra = new ArrayList<>();
            List<LoteEnvio> inter = new ArrayList<>();
            for (LoteEnvio b : bloqueBatches) {
                if (b.getSlaLimitHours() <= 24) intra.add(b);
                else inter.add(b);
            }
            for (LoteEnvio b : intra) {
                if (System.nanoTime() >= deadlineMotorNs) break;
                enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport);
            }
            for (LoteEnvio b : inter) {
                if (System.nanoTime() >= deadlineMotorNs) break;
                enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport);
            }

            long restanteAlnsMs = Math.max(0L, (deadlineMotorNs - System.nanoTime()) / 1_000_000L);
            if (restanteAlnsMs > 0 && bloqueBatches.stream().anyMatch(b -> !b.isCumpleSLA())) {
                AlgoritmoALNS alns = new AlgoritmoALNS(
                        graph, enrutador, bloqueBatches, blockFlight, blockAirport, props);
                if (rngSim != null) alns.setRandom(rngSim);

                double umbralCerca = 0.10;
                int iteraciones = (ctx.tasaSinRutaPrevia >= umbralCerca)
                        ? props.getAlns().getIteracionesCercaColapso()
                        : props.getAlns().getIteracionesBase();

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

        boolean colapsoAlmacen = false;
        String detalleColapso = null;
        for (LoteEnvio b : finalBatches) {
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (enrutada && b.isCumpleSLA()) continue;   // on-time: ningún almacén lo bloqueó
            if (enrutador.sinRutaPorAlmacenLleno(b)) {
                colapsoAlmacen = true;
                detalleColapso = b.getId() + " " + b.getOriginCode() + "->" + b.getDestCode()
                        + (enrutada ? " (desviado tardío por almacén lleno)" : "");
                break;
            }
        }

        if (backlog != null) {
            boolean motorAco = MOTOR_ACO.equalsIgnoreCase(motor);
            double umbralSlack = props.getBacklog().getUmbralReplanificacionSlack();
            for (LoteEnvio b : finalBatches) {
                boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
                if (!enrutada) {
                    backlog.addSinRuta(b);
                } else if (!motorAco && b.isCumpleSLA() && b.getSlaSlackRatio() < umbralSlack) {
                    // Próximo a tardar — candidato a replanificación preventiva.
                    backlog.addReplanificable(b);
                }
            }
            LoteEnvio origenDesbordado = enrutador.reconstruirEsperaOrigenBacklog(
                    backlog.peekPendientes(), bloqueBatches);
            if (origenDesbordado != null && !colapsoAlmacen) {
                colapsoAlmacen = true;
                detalleColapso = "origen lleno " + origenDesbordado.getId()
                        + " @" + origenDesbordado.getOriginCode();
            }
        }

        if (auditAcc != null) {
            for (LoteEnvio b : finalBatches) auditAcc.registrar(b);
        }

        ctx.marcarFin(taFijoMs);

        BloqueSimulacion bloque = new BloqueSimulacion();
        bloque.setHoraInicio(ctx.scStart.toString());
        bloque.setHoraFin(ctx.scEnd.toString());
        bloque.setHoraInicioUtc(ctx.scStart.toString());
        bloque.setHoraFinUtc(ctx.scEnd.toString());
        bloque.setMaletasProcesadas(finalBatches.size());
        bloque.setMaletasEnrutadas(enrutadas);
        bloque.setAsignaciones(asignaciones);
        bloque.setCargasVuelos(buildCargasVuelos(telemetryFlight, graph, enrutador));
        bloque.setOcupacionAlmacenes(buildOcupacionAlmacenes(telemetryAirport, graph, enrutador));
        bloque.setAlertaAlmacen(construirAlertaAlmacen(bloque.getOcupacionAlmacenes(), ctx.bloqueIdx));
        bloque.setBloqueIdx(ctx.bloqueIdx);
        bloque.setTaMs(ctx.taMs);
        bloque.setScMinutos(ctx.scMinutos);
        if (auditAcc != null) auditAcc.llenarAcumuladosFisicos(bloque);

        var pre = enrutador.evaluarPreColapso(
                telemetryAirport, backlog != null ? backlog.peekPendientes() : java.util.List.of());
        com.tasfb2b.planificador.dto.trabajos.AlertaColapso alerta = construirAlertaColapso(pre, ctx.bloqueIdx);

        if (!colapsoAlmacen && pre.utilAlmacenMax() > 1.0) {
            colapsoAlmacen = true;
            detalleColapso = "desborde de almacén " + pre.almacenCritico() + " al "
                    + Math.round(pre.utilAlmacenMax() * 100.0) + "% de capacidad";
        }

        List<OcupacionAlmacenSlot> serieAlmacenes = fastForward
                ? List.of()
                : buildSerieAlmacenes(telemetryAirport, graph, enrutador);

        return new ResultadoVentana(bloque, finalBatches.size(), enrutadas, sinRuta, cumpleSLA, tardadas, maletas,
                colapsoAlmacen, detalleColapso, alerta, serieAlmacenes, finalBatches);
    }

    private com.tasfb2b.planificador.dto.trabajos.AlertaColapso construirAlertaColapso(
            OperadorReparacionVoraz.PreColapso pre, int bloque) {
        var cfg = props.getAlertaColapso();
        String nivelAlmacen = com.tasfb2b.planificador.dto.trabajos.AlertaColapso.VERDE;
        if (pre.utilAlmacenMax() >= cfg.getAlmacenRojo()) nivelAlmacen = com.tasfb2b.planificador.dto.trabajos.AlertaColapso.ROJO;
        else if (pre.utilAlmacenMax() >= cfg.getAlmacenAmbar()) nivelAlmacen = com.tasfb2b.planificador.dto.trabajos.AlertaColapso.AMBAR;
        String nivelBacklog = com.tasfb2b.planificador.dto.trabajos.AlertaColapso.VERDE;
        if (pre.envioUrgente() != null) {
            if (pre.holguraSlaMin() <= cfg.getSlaRestanteRojo()) nivelBacklog = com.tasfb2b.planificador.dto.trabajos.AlertaColapso.ROJO;
            else if (pre.holguraSlaMin() <= cfg.getSlaRestanteAmbar()) nivelBacklog = com.tasfb2b.planificador.dto.trabajos.AlertaColapso.AMBAR;
        }
        String nivel = nivelMax(nivelAlmacen, nivelBacklog);

        StringBuilder msg = new StringBuilder();
        if (!com.tasfb2b.planificador.dto.trabajos.AlertaColapso.VERDE.equals(nivelAlmacen)) {
            msg.append(String.format("almacén %s al %.0f%% de capacidad",
                    pre.almacenCritico(), pre.utilAlmacenMax() * 100));
        }
        if (!com.tasfb2b.planificador.dto.trabajos.AlertaColapso.VERDE.equals(nivelBacklog)) {
            if (msg.length() > 0) msg.append(" | ");
            msg.append(String.format("envío %s al %.0f%% de su SLA en backlog",
                    pre.envioUrgente(), Math.max(0, pre.holguraSlaMin()) * 100));
        }
        if (msg.length() == 0) msg.append("Sin riesgo de colapso");

        boolean almacenActivo = !com.tasfb2b.planificador.dto.trabajos.AlertaColapso.VERDE.equals(nivelAlmacen);
        boolean backlogActivo = !com.tasfb2b.planificador.dto.trabajos.AlertaColapso.VERDE.equals(nivelBacklog);
        String causaDominante = almacenActivo && backlogActivo ? "ambos"
                : almacenActivo ? "almacen"
                : backlogActivo ? "sla"
                : null;

        return new com.tasfb2b.planificador.dto.trabajos.AlertaColapso(
                nivel, msg.toString(), bloque,
                pre.utilAlmacenMax(), pre.almacenCritico(), pre.holguraSlaMin(), pre.envioUrgente(),
                causaDominante);
    }

    private String avisarColapsoInminente(String escenario, AlertaColapso alerta, int bloque, String nivelPrevio) {
        if (alerta == null) return nivelPrevio;
        String nivel = alerta.getNivel();
        if (!AlertaColapso.VERDE.equals(nivel) && !nivel.equals(nivelPrevio)) {
            log.warn("{} ⚠ COLAPSO INMINENTE [{}] bloque {} — {}", escenario, nivel, bloque, alerta.getMensaje());
        }
        return nivel;
    }

    private static String nivelMax(String a, String b) {
        if (com.tasfb2b.planificador.dto.trabajos.AlertaColapso.ROJO.equals(a)
                || com.tasfb2b.planificador.dto.trabajos.AlertaColapso.ROJO.equals(b))
            return com.tasfb2b.planificador.dto.trabajos.AlertaColapso.ROJO;
        if (com.tasfb2b.planificador.dto.trabajos.AlertaColapso.AMBAR.equals(a)
                || com.tasfb2b.planificador.dto.trabajos.AlertaColapso.AMBAR.equals(b))
            return com.tasfb2b.planificador.dto.trabajos.AlertaColapso.AMBAR;
        return com.tasfb2b.planificador.dto.trabajos.AlertaColapso.VERDE;
    }

    private List<ContextoTemporal> construirPlanBloques(int k) {
        return construirPlanBloques(k, null, null, null);
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
        LocalDateTime scStart = inicio;
        int idx = 0;
        while (scStart.isBefore(fin)) {
            LocalDateTime scEnd = scStart.plusMinutes(scMin);
            if (scEnd.isAfter(fin)) scEnd = fin;
            plan.add(new ContextoTemporal(scStart, scEnd, scMin, saMin, k, idx++));
            scStart = scEnd;
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
        LocalDateTime primero = dataLoader.getPrimeraVentana();
        LocalDateTime ultimo = dataLoader.getUltimaVentana();
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
        LocalDateTime scStart = inicio;
        int idx = 0;
        while (scStart.isBefore(fin)) {
            LocalDateTime scEnd = scStart.plusMinutes(scMin);
            if (scEnd.isAfter(fin)) scEnd = fin;
            plan.add(new ContextoTemporal(scStart, scEnd, scMin, saMin, k, idx++));
            scStart = scEnd;
        }
        return plan;
    }

    private List<ContextoTemporal> construirPlanWarmup(int k,
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

        if (!fechaInicio.isAfter(primero)) return Collections.emptyList();
        if (!fechaInicio.isBefore(ultimo.plusMinutes(saMin))) return Collections.emptyList();

        LocalDateTime fin = alinearASa(fechaInicio, primero, saMin);
        if (!primero.isBefore(fin)) return Collections.emptyList();

        log.info("Plan warm-up: inicio={} fin={} K={} Sa={}min Sc={}min",
                primero, fin, k, saMin, scMin);

        List<ContextoTemporal> plan = new ArrayList<>();
        LocalDateTime scStart = primero;
        int idx = 0;
        while (scStart.isBefore(fin)) {
            LocalDateTime scEnd = scStart.plusMinutes(scMin);
            if (scEnd.isAfter(fin)) scEnd = fin;
            plan.add(new ContextoTemporal(scStart, scEnd, scMin, saMin, k, idx++));
            scStart = scEnd;
        }
        return plan;
    }

    private static LocalDateTime alinearASa(LocalDateTime t, LocalDateTime base, int saMin) {
        long minutosDesdeBase = java.time.Duration.between(base, t).toMinutes();
        long alineado = (minutosDesdeBase / saMin) * saMin;
        return base.plusMinutes(alineado);
    }

    public EnvioEstadoResponse buscarEstadoEnvio(String jobId, String idEnvio, LocalDateTime instante) {
        AsignacionMaleta asig = construirAsignacionDesdeBd(jobId, idEnvio);
        if (asig == null) asig = construirAsignacionSintetica(jobId, idEnvio);   // inyectados/registrados EN VIVO
        if (asig == null) return null;
        LocalDateTime ahora = (instante != null) ? instante : ahoraDelJob(jobId);
        EnvioEstadoResponse resp = CalculadorEstadoEnvio.calcular(asig, ahora);
        resp.setInstanteDerivadoDelJob(instante == null && ahora != null);
        return resp;
    }

    private AsignacionMaleta construirAsignacionSintetica(String jobId, String idEnvio) {
        if (idEnvio == null || !idEnvio.startsWith("INV-")) return null;
        EstadoTrabajo job = getJob(jobId);
        if (job != null) {
            AsignacionMaleta enRam = job.getRutaSintetica(idEnvio);
            if (enRam != null) return enRam;
        }
        if (!persistencia.reflejaEnBd(jobId)) return null;
        Grafo graph = motorCache.obtenerGrafo(
                () -> mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos()));
        Map<String, Arista> indiceVuelo = solucionBdReader.construirIndiceVuelo(graph);
        return solucionBdReader.buscarPorEnvioInyectado(idEnvio, indiceVuelo)
                .map(b -> buildAsignaciones(List.of(b)).get(0))
                .orElse(null);
    }

    private AsignacionMaleta construirAsignacionDesdeBd(String jobId, String idEnvio) {
        if (!persistencia.reflejaEnBd(jobId)) return null;
        Grafo graph = motorCache.obtenerGrafo(
                () -> mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos()));
        Map<String, Arista> indiceVuelo = solucionBdReader.construirIndiceVuelo(graph);
        return solucionBdReader.buscarPorEnvio(idEnvio, indiceVuelo)
                .map(b -> buildAsignaciones(List.of(b)).get(0))
                .orElse(null);
    }

    private LocalDateTime ahoraDelJob(String jobId) {
        EstadoTrabajo job = getJob(jobId);
        if (job == null) return null;
        BloqueSimulacion ultimo = job.ultimoBloque();
        if (ultimo == null || ultimo.getHoraFin() == null) return null;
        try {
            return LocalDateTime.parse(ultimo.getHoraFin());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private LoteEnvio reenrutarAfectadoDesdePosicion(LoteEnvio b, ContextoTemporal ctx,
            Grafo graph, OperadorReparacionVoraz enrutador,
            Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        if (!b.tienePrefijo()) {
            List<Arista> route = b.getAssignedRoute();
            List<Long> deps  = b.getAssignedDepartures();
            if (route == null || route.isEmpty() || deps == null || deps.size() != route.size()) {
                enrutador.releaseFromGlobal(b);
                b.clearRoute();
                return enrutarSufijo(b, enrutador, blockFlight, blockAirport);   // desde el origen
            }
            long ahoraMin = OperadorReparacionVoraz.toEpochMinPublic(ctx.scEnd);
            int n = route.size();
            int k = n;
            for (int i = 0; i < n; i++) {
                if (deps.get(i) > ahoraMin) { k = i; break; }   // primer tramo PENDIENTE
            }
            if (k == n) {
                return b;   // ya en el último vuelo / entregado: no tocar (el cancelado ya se voló)
            }
            if (k == 0) {
                enrutador.releaseFromGlobal(b);   // aún no salió: re-enrutar completo desde el origen
                b.clearRoute();
                return enrutarSufijo(b, enrutador, blockFlight, blockAirport);
            }
            Arista cortEdge = route.get(k - 1);
            long arrCorte = deps.get(k - 1) + cortEdge.durationMinutes;
            enrutador.releaseSuffixFromGlobal(b, k);
            b.setPrefijoFijo(new ArrayList<>(route.subList(0, k)));
            b.setPrefijoFijoDepartures(new ArrayList<>(deps.subList(0, k)));
            b.setCurrentOriginCode(cortEdge.to.code);
            b.setCurrentReadyTime(epochMinToLdt(arrCorte));
            b.setAssignedRoute(new ArrayList<>());
            b.setAssignedDepartures(null);
        }
        return enrutarSufijo(b, enrutador, blockFlight, blockAirport);
    }

    private LoteEnvio enrutarSufijo(LoteEnvio b, OperadorReparacionVoraz enrutador,
            Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        LoteEnvio sintetico = new LoteEnvio(b.getId(), b.getQuantity(), b.getSlaLimitHours(),
                b.origenEfectivo(), b.getDestCode(), b.readyEfectivo());
        List<OperadorReparacionVoraz.RutaCandidata> candidatos = enrutador.generarCandidatosRuta(
                sintetico, blockFlight, blockAirport, SUFIJO_ROUTE_CANDIDATES);
        OperadorReparacionVoraz.RutaCandidata elegido = elegirSufijo(candidatos, b, enrutador);
        if (elegido == null) {
            b.setAssignedRoute(new ArrayList<>());
            b.setAssignedDepartures(null);
            b.setCumpleSLA(false);
            return b;
        }
        enrutador.aplicarCandidatoBloque(sintetico, elegido, blockFlight, blockAirport);
        b.setAssignedRoute(new ArrayList<>(elegido.getEdges()));
        b.setAssignedDepartures(new ArrayList<>(elegido.getActualDepartures()));
        b.setCumpleSLA(enrutador.cumpleSlaDesdeOrigen(elegido, b));   // SLA desde el origen ORIGINAL
        return b;
    }

    private OperadorReparacionVoraz.RutaCandidata elegirSufijo(
            List<OperadorReparacionVoraz.RutaCandidata> candidatos, LoteEnvio original,
            OperadorReparacionVoraz enrutador) {
        if (candidatos == null) return null;
        OperadorReparacionVoraz.RutaCandidata mejorOnTime = null, mejorTardio = null;
        for (OperadorReparacionVoraz.RutaCandidata c : candidatos) {
            if (enrutador.cumpleSlaDesdeOrigen(c, original)) {
                if (mejorOnTime == null || c.getArrivalMin() < mejorOnTime.getArrivalMin()) mejorOnTime = c;
            } else {
                if (mejorTardio == null || c.getArrivalMin() < mejorTardio.getArrivalMin()) mejorTardio = c;
            }
        }
        return mejorOnTime != null ? mejorOnTime : mejorTardio;
    }

    private static LocalDateTime epochMinToLdt(long epochMin) {
        long day = Math.floorDiv(epochMin, 1440L);
        int minOfDay = (int) Math.floorMod(epochMin, 1440L);
        return LocalDate.ofEpochDay(day).atTime(minOfDay / 60, minOfDay % 60);
    }

    List<AsignacionMaleta> buildAsignaciones(List<LoteEnvio> batches) {
        return batches.stream().map(b -> {
            List<Arista> rutaCompleta = b.getRutaCompleta();
            List<Long> depsCompletas = b.getDeparturesCompletas();
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            boolean tieneTramos = rutaCompleta != null && !rutaCompleta.isEmpty()
                    && depsCompletas != null && depsCompletas.size() == rutaCompleta.size();
            AsignacionMaleta asig = new AsignacionMaleta();
            asig.setBatchId(b.getId());
            asig.setOrigen(b.getOriginCode());
            asig.setDestino(b.getDestCode());
            asig.setCantidad(b.getQuantity());
            asig.setEnrutada(enrutada);
            asig.setCumpleSLA(b.isCumpleSLA());
            asig.setRutaVuelos(tieneTramos
                    ? rutaCompleta.stream().map(e -> e.id).collect(Collectors.toList())
                    : Collections.emptyList());

            LocalDateTime ready = b.getReadyTime();
            if (ready != null) {
                long readyUtcMin = toEpochMin(ready);
                asig.setRegistroUtc(epochMinToIso(readyUtcMin));
                asig.setRegistroLocal(epochMinToIso(readyUtcMin + offsetHoras(b.getOriginCode()) * 60L));
            }

            List<TramoRuta> tramos = Collections.emptyList();
            if (tieneTramos) {
                var route = rutaCompleta;
                var deps = depsCompletas;
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
                    tr.setSalidaUtc(epochMinToIso(depMin));
                    tr.setLlegadaUtc(epochMinToIso(arrMin));
                    tr.setSalidaLocal(epochMinToIso(depMin + offsetHoras(origen) * 60L));
                    tr.setLlegadaLocal(epochMinToIso(arrMin + offsetHoras(destino) * 60L));
                    tr.setDuracionMin(edge.durationMinutes);
                    tramos.add(tr);
                }
            }
            asig.setTramos(tramos);
            return asig;
        }).collect(Collectors.toList());
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

    List<CargaVuelo> buildCargasVuelos(Map<Long, Integer> blockFlight, Grafo graph,
                                                          OperadorReparacionVoraz enrutador) {
        if (blockFlight == null || blockFlight.isEmpty() || graph == null) return List.of();

        Map<Integer, Arista> edgesByIdx = new HashMap<>();
        for (Arista edge : graph.edges) edgesByIdx.put(edge.idx, edge);

        List<CargaVuelo> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : blockFlight.entrySet()) {
            int carga = enrutador != null
                    ? enrutador.ocupacionGlobalVuelo(entry.getKey())
                    : entry.getValue();
            if (carga <= 0) continue;
            Arista edge = edgesByIdx.get(resourceIdx(entry.getKey()));
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
            FormatoSimulacion.completarCargaVuelo(dto);
            out.add(dto);
        }
        out.sort(Comparator.comparing(CargaVuelo::getFechaSalida)
                .thenComparing(CargaVuelo::getVueloId));
        return out;
    }

    List<OcupacionAlmacen> buildOcupacionAlmacenes(Map<Long, Integer> blockAirport,
                                                                       Grafo graph,
                                                                       OperadorReparacionVoraz enrutador) {
        if (blockAirport == null || blockAirport.isEmpty() || graph == null) return List.of();

        Map<Integer, Nodo> nodesByIdx = new HashMap<>();
        for (Nodo node : graph.nodes.values()) nodesByIdx.put(node.idx, node);

        Map<Long, Integer> picoPorAeroDia = new LinkedHashMap<>();   // clave (nodeIdx, epochDay) → pico
        for (Map.Entry<Long, Integer> entry : blockAirport.entrySet()) {
            int ocupacion = enrutador != null
                    ? enrutador.ocupacionGlobalAlmacen(entry.getKey())
                    : entry.getValue();
            if (ocupacion <= 0) continue;
            int nodeIdx = resourceIdx(entry.getKey());
            long slot = entry.getKey() & CodificadorClaveVuelo.DAY_MASK;   // índice de slot (epochMin/60)
            long epochDia = (slot * OperadorReparacionVoraz.STORAGE_SLOT_MIN) / CodificadorClaveVuelo.DAY_MIN;
            long claveAeroDia = (((long) nodeIdx) << CodificadorClaveVuelo.DAY_BITS) | (epochDia & CodificadorClaveVuelo.DAY_MASK);
            picoPorAeroDia.merge(claveAeroDia, ocupacion, Integer::max);
        }

        List<OcupacionAlmacen> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : picoPorAeroDia.entrySet()) {
            Nodo node = nodesByIdx.get(resourceIdx(entry.getKey()));
            if (node == null) continue;

            OcupacionAlmacen dto = new OcupacionAlmacen();
            dto.setAeropuerto(node.code);
            dto.setFecha(LocalDate.ofEpochDay(epochDay(entry.getKey())).toString());
            dto.setCapacidadMaxima(node.capacity);
            dto.setOcupacionAsignada(entry.getValue());   // pico concurrente del día
            FormatoSimulacion.completarOcupacionAlmacen(dto);
            out.add(dto);
        }
        out.sort(Comparator.comparing(OcupacionAlmacen::getFecha)
                .thenComparing(OcupacionAlmacen::getAeropuerto));
        return out;
    }

    List<OcupacionAlmacenSlot> buildSerieAlmacenes(Map<Long, Integer> blockAirport,
                                                                      Grafo graph,
                                                                      OperadorReparacionVoraz enrutador) {
        if (blockAirport == null || blockAirport.isEmpty() || graph == null) return List.of();

        Map<Integer, Nodo> nodesByIdx = new HashMap<>();
        for (Nodo node : graph.nodes.values()) nodesByIdx.put(node.idx, node);

        List<OcupacionAlmacenSlot> out = new ArrayList<>(blockAirport.size());
        for (Map.Entry<Long, Integer> entry : blockAirport.entrySet()) {
            int ocupacion = enrutador != null
                    ? enrutador.ocupacionGlobalAlmacen(entry.getKey())
                    : entry.getValue();
            if (ocupacion <= 0) continue;
            Nodo node = nodesByIdx.get(resourceIdx(entry.getKey()));
            if (node == null) continue;

            long slot = entry.getKey() & CodificadorClaveVuelo.DAY_MASK;   // índice de slot (epochMin/60)
            long epochMin = slot * OperadorReparacionVoraz.STORAGE_SLOT_MIN;

            OcupacionAlmacenSlot dto = new OcupacionAlmacenSlot();
            dto.setAeropuerto(node.code);
            dto.setHora(epochMinToIso(epochMin));
            dto.setCapacidadMaxima(node.capacity);
            dto.setOcupacion(ocupacion);
            double porcentaje = FormatoSimulacion.porcentaje(ocupacion, node.capacity);
            dto.setPorcentajeOcupacion(porcentaje);
            dto.setSemaforo(FormatoSimulacion.semaforoPorPorcentaje(porcentaje));
            out.add(dto);
        }
        out.sort(Comparator.comparing(OcupacionAlmacenSlot::getHora)
                .thenComparing(OcupacionAlmacenSlot::getAeropuerto));
        return out;
    }

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
        return (int) (key >> CodificadorClaveVuelo.DAY_BITS);
    }

    private static long epochDay(long key) {
        return key & CodificadorClaveVuelo.DAY_MASK;
    }


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
                ? ChronoUnit.DAYS.between(AnalizadorVuelos.FLIGHT_BASE_DATE, simulationDate) : 0L;

        List<VueloBackend> vuelosFront = new ArrayList<>();
        Map<String, AeropuertoDTO> infoAero = new HashMap<>();
        for (Vuelo v : vuelosReales) {
            VueloBackend vb = new VueloBackend();
            vb.setId(FormatoSimulacion.vueloFrontId(v));
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
                                       boolean collapso, int bloqueCollapso,
                                       String motivoColapso, String detalleColapso,
                                       LocalDateTime instanteColapso) {
        m.setProcesadas(envios);
        m.setEnrutadas(enrutadas);
        m.setSinRuta(sinRuta);
        m.setCumpleSLA(cumpleSLA);
        m.setTardadas(tardadas);
        m.setMaletasIndividuales(maletas);
        m.setVuelosCancelados(vuelosCancelados);
        m.setCollapsoDetectado(collapso);
        m.setBloqueColapso(bloqueCollapso);
        // Detalle del colapso real (null si no hubo): causa, dónde/qué e instante UTC.
        m.setMotivoColapso(motivoColapso);
        m.setDetalleColapso(detalleColapso);
        m.setInstanteColapsoUtc(instanteColapso != null ? instanteColapso.toString() : null);
    }

    private static void llenarMetricasTa(Metricas m, EstadisticasTa stats, long saMs) {
        m.setTaMinMs(stats.min());
        m.setTaMaxMs(stats.max());
        m.setTaPromedioMs(stats.promedio());
        m.setTiempoTotalAlgMs(stats.suma());
        m.setAdvertenciaCalibracion(stats.max() > saMs * 0.9);
    }

    private AcumuladorAuditoria ejecutarWarmup(List<ContextoTemporal> warmupPlan, EstadoTrabajo job,
                                                     Grafo graph, OperadorReparacionVoraz enrutador,
                                                     SolucionAlns solucionDummy, Map<String, int[]> odStats,
                                                     GestorBacklog backlog, String motorRes, long seed,
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
        for (ContextoTemporal ctx : warmupPlan) {
            wIdx++;
            Random rngBloque = rngParaBloque(seed, motorRes, ctx.bloqueIdx);
            procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog,
                    auditWarmup, motorRes, rngBloque, taFijoMs, true, false);
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

    List<AsignacionMaleta> construirEstadoInicial(Collection<LoteEnvio> batchesWarmup) {
        if (batchesWarmup == null || batchesWarmup.isEmpty()) return List.of();

        long relojMin = Long.MIN_VALUE;
        for (LoteEnvio b : batchesWarmup) {
            long readyMin = toEpochMin(b.getReadyTime());
            if (readyMin > relojMin) relojMin = readyMin;
        }

        List<LoteEnvio> activos = new ArrayList<>();
        for (LoteEnvio b : batchesWarmup) {
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (enrutada && ultimoArriboMin(b) > relojMin) activos.add(b);
        }
        return buildAsignaciones(activos);
    }

    private static GestorBacklog crearBacklogConPurga(OperadorReparacionVoraz enrutador) {
        return new GestorBacklog(0, true, b -> {
            if (enrutador.rutaUsaVueloCancelado(b)) {
                enrutador.releaseFromGlobal(b);
                b.clearRoute();
            }
        });
    }

    private static boolean cancelacionPedida(EstadoTrabajo job) {
        return job != null && ("cancelado".equals(job.estado) || job.canceladoPorUsuario);
    }

    private static Random rngParaBloque(long seed, String motor, int bloqueIdx) {
        long mixed = seed
                ^ ((long) bloqueIdx * 0x9E3779B97F4A7C15L)
                ^ ((long) (motor != null ? motor.hashCode() : 0) << 32);
        return new Random(mixed);
    }

    private record ResumenEnvio(long quantity, boolean enrutada, boolean cumpleSLA,
                                long readyMin, long ultimoArriboMin) {
        static ResumenEnvio de(LoteEnvio b) {
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            return new ResumenEnvio(b.getQuantity(), enrutada, b.isCumpleSLA(),
                    PlanificadorService.toEpochMin(b.getReadyTime()),
                    PlanificadorService.ultimoArriboMin(b));
        }
    }


    static final class AcumuladorAuditoria {
        private final Map<String, ResumenEnvio> resumen = new LinkedHashMap<>();
        private final Map<String, LoteEnvio> sinRuta = new LinkedHashMap<>();
        private final Map<String, LoteEnvio> completos;

        AcumuladorAuditoria(boolean retenerBatches) {
            this.completos = retenerBatches ? new LinkedHashMap<>() : null;
        }

        void registrar(LoteEnvio b) {
            String key = batchAuditKey(b);
            if (completos != null) { completos.put(key, b); return; }   // warm-up: solo completos
            resumen.put(key, ResumenEnvio.de(b));
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (enrutada) sinRuta.remove(key);              // pasó de sin-ruta a enrutado
            else sinRuta.put(key, b.cloneBatch());          // snapshot ligero (sin ruta) para el ZIP
        }

        boolean isEmpty()                    { return resumen.isEmpty(); }
        Collection<LoteEnvio> sinRuta()   { return sinRuta.values(); }
        int sinRutaSize()                    { return sinRuta.size(); }   // Fase 0/E3: medición de huella
        Collection<LoteEnvio> completos() { return completos != null ? completos.values() : List.of(); }

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

    private static com.tasfb2b.planificador.dto.simulacion.Metricas metricasSnapshotDe(TotalesUnicos t, long taPromedioMs) {
        com.tasfb2b.planificador.dto.simulacion.Metricas m = new com.tasfb2b.planificador.dto.simulacion.Metricas();
        m.setProcesadas(t.envios());
        m.setEnrutadas(t.enrutadas());
        m.setSinRuta(t.sinRuta());
        m.setCumpleSLA(t.cumpleSLA());
        m.setTardadas(t.tardadas());
        m.setMaletasIndividuales(t.maletas());
        m.setTaPromedioMs(taPromedioMs);
        return m;
    }

    private static long ultimoArriboMin(LoteEnvio b) {
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

    private static String batchAuditKey(LoteEnvio b) {
        if (b == null) return "";
        return String.join("|",
                FormatoSimulacion.safe(b.getId()),
                FormatoSimulacion.safe(b.getOriginCode()),
                FormatoSimulacion.safe(b.getDestCode()),
                b.getReadyTime() != null ? b.getReadyTime().toString() : "",
                String.valueOf(b.getQuantity()));
    }

    private static void llenarMetricasBacklog(Metricas m, GestorBacklog backlog) {
        m.setBacklogActual(backlog.size());
        m.setBacklogPico(backlog.picoHistorico());
        m.setSinRutaDefinitivo(backlog.sinRutaDefinitivo());
    }

    private static String epochMinToIso(long epochMin) {
        long epochDay = Math.floorDiv(epochMin, 1440L);
        int minuteOfDay = (int) Math.floorMod(epochMin, 1440L);
        return LocalDateTime.of(
                LocalDate.ofEpochDay(epochDay),
                LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        ).toString();
    }

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
            // Mismo gmt que /aeropuertos: el front recibe el offset por cualquiera de los dos caminos.
            dto.setGmt(a.getOffsetHorario() != null ? a.getOffsetHorario().doubleValue() : 0.0);
            map.put(cod, dto);
        }
    }

    private void logBloque(String motor, int bloque, int total, int envios, int onTime,
                           int tardadas, int sinRuta, long taMs, int backlog, boolean colapso,
                           EstadoTrabajo job, int sinRutaRam) {
        log.info("Bloque {}/{} [{}] | envíos:{} | onTime:{} | tardadas:{} | sinRuta:{} | Ta:{}ms | backlog:{}{}",
                bloque, total, motor, envios, onTime, tardadas, sinRuta, taMs, backlog,
                colapso ? " | COLAPSO" : "");
        // Fase 0 (medición anti-OOM): huella de memoria periódica (heap + acumuladores del job).
        if (job != null && (bloque % 50 == 0 || bloque == total)) logHuellaMemoria(job, sinRutaRam);
    }


    private void logHuellaMemoria(EstadoTrabajo job, int sinRutaRam) {
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
            com.tasfb2b.planificador.dto.trabajos.AlertaColapso alerta,
            List<OcupacionAlmacenSlot> serieAlmacenes,
            List<LoteEnvio> finalBatches) {
    }

    private record TotalesUnicos(
            int envios, int enrutadas, int sinRuta, int cumpleSLA, int tardadas, long maletas) {
    }

    private static final class EstadisticasTa {
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
