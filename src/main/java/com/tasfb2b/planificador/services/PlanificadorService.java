package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.algorithm.alns.*;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.Vuelo;
import com.tasfb2b.planificador.util.AlgorithmMapper;
import com.tasfb2b.planificador.util.DataLoader;
import com.tasfb2b.planificador.util.FlightCancellationSimulator;
import com.tasfb2b.planificador.util.FlightParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlanificadorService {

    private final DataLoader              dataLoader;
    private final AlgorithmMapper         mapper;
    private final PlanificadorProperties  props;
    private final JobsRegistry            jobs;

    // ── Caché escenario 2 (período) ─────────────────────────────────────────
    private volatile List<SimulacionResponse.BloqueSimulacion> bloquesCacheados = null;

    // ── Estado escenario 1 (día a día) ───────────────────────────────────────
    private volatile Graph                                     sc1Graph      = null;
    private volatile GreedyRepairOperator                      sc1Enrutador  = null;
    private volatile AlnsSolution                              sc1Dummy      = null;
    private volatile List<TemporalContext>                     sc1Plan       = null;
    private volatile int                                       sc1Idx        = 0;
    private volatile int                                       sc1Envios     = 0;
    private volatile int                                       sc1Enrutadas  = 0;
    private volatile int                                       sc1SinRuta    = 0;
    private volatile int                                       sc1CumpleSLA  = 0;
    private volatile int                                       sc1Tardadas   = 0;
    private volatile long                                      sc1Maletas    = 0L;
    private volatile TaStats                                   sc1TaStats    = new TaStats();
    private volatile BacklogManager                            sc1Backlog    = null;
    private volatile List<SimulacionResponse.BloqueSimulacion> sc1Bloques    = new ArrayList<>();
    private final    Map<String, int[]>                        sc1OdStats    = new HashMap<>();

    public PlanificadorService(DataLoader dataLoader,
                                AlgorithmMapper mapper,
                                PlanificadorProperties props,
                                JobsRegistry jobs) {
        this.dataLoader = dataLoader;
        this.mapper     = mapper;
        this.props      = props;
        this.jobs       = jobs;
    }

    // =========================================================
    // Lanzadores async (escenarios 2 y 3)
    // =========================================================

    /**
     * Lanza el escenario 2 de forma asíncrona. Devuelve inmediatamente con el
     * jobId; el cliente puede consultar progreso/resultado en endpoints separados.
     */
    public JobState iniciarEscenario2Async(int k, double cancelProb) {
        JobState job = jobs.crear("2", k);
        jobs.ejecutar(job, () -> {
            SimulacionResponse res = ejecutarALNS(k, cancelProb, job);
            job.resultado = res;
        });
        return job;
    }

    /** Análogo a {@link #iniciarEscenario2Async} pero para escenario 3. */
    public JobState iniciarEscenario3Async(int k, double cancelProb, double umbralColapso) {
        JobState job = jobs.crear("3", k);
        jobs.ejecutar(job, () -> {
            SimulacionResponse res = ejecutarHastaColapso(k, cancelProb, umbralColapso, job);
            job.resultado = res;
        });
        return job;
    }

    /** Atajo para consultar estado de un job. */
    public JobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    /** Cancela un job en ejecución. */
    public boolean cancelarJob(String jobId) {
        return jobs.cancelar(jobId);
    }

    // =========================================================
    // Escenario 2: Simulación de período (batch completo)
    // =========================================================
    public SimulacionResponse ejecutarALNS(int k, double cancelProb) {
        return ejecutarALNS(k, cancelProb, null);
    }

    public SimulacionResponse ejecutarALNS(int k, double cancelProb, JobState job) {
        int saMin = props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);
        log.info("Escenario 2 — ALNS ({} iters/bloque, K={}, Sa={}min, Sc={}min, cancelProb={}%, async={}) ...",
                props.getAlns().getIteracionesBase(), k, saMin, scMin,
                String.format("%.1f", cancelProb * 100),
                job != null);
        long inicio = System.currentTimeMillis();

        List<TemporalContext> plan = construirPlanBloques(k);
        if (plan.isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k); r.setSaMinutos(saMin);
            return r;
        }

        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AlnsSolution solucionDummy     = new AlnsSolution(Collections.emptyList());

        int totalBloques     = plan.size();
        int intervaloReporte = Math.max(1, totalBloques / 10);

        int totalVuelosCancelados = 0;
        if (cancelProb > 0.0) {
            long startDay = plan.get(0).scStart.toLocalDate().toEpochDay();
            long endDay   = plan.get(plan.size() - 1).scEnd.toLocalDate().toEpochDay() + 3;
            Set<Long> cancelados = FlightCancellationSimulator.generate(
                    graph.edges, startDay, endDay, cancelProb);
            enrutador.setCancelledFlights(cancelados);
            totalVuelosCancelados = cancelados.size();
            log.info("Cancelaciones: {} vuelo-días", totalVuelosCancelados);
        }

        List<SimulacionResponse.BloqueSimulacion> bloques = new ArrayList<>(totalBloques);
        Map<String, int[]> odStats = new HashMap<>();
        int  totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
             totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        TaStats taStats = new TaStats();
        boolean simularTiempoReal = props.getScenario().isSimularTiempoReal2();
        long saMs = saMin * 60_000L;
        BacklogManager backlog = new BacklogManager(
                props.getBacklog().getMaxSize(),
                props.getBacklog().isPurgarVencidas());

        for (TemporalContext ctx : plan) {
            bloqueActual++;
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog);
            bloques.add(rv.bloque);
            taStats.acumular(ctx.taMs);

            totalEnvios    += rv.envios;
            totalEnrutadas += rv.enrutadas;
            totalSinRuta   += rv.sinRuta;
            totalCumpleSLA += rv.cumpleSLA;
            totalTardadas  += rv.tardadas;
            totalMaletas   += rv.maletas;

            // Reporte de progreso al job (si está siendo ejecutado de forma async)
            if (job != null) {
                job.bloqueActual = bloqueActual;
                job.totalBloques = totalBloques;
                job.taPromedioMs = taStats.promedio();
            }

            // Propagar tasa sinRuta al siguiente bloque para iteraciones dinámicas
            if (bloqueActual < plan.size()) {
                double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
                plan.get(bloqueActual).tasaSinRutaPrevia = tasa;
            }

            if (bloqueActual % intervaloReporte == 0 || bloqueActual == totalBloques) {
                log.info("Progreso E2: {}% — {}/{} | envíos:{} maletas:{} | ok:{} tarde:{} sinRuta:{} | Ta={}ms",
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
                    try { Thread.sleep(dormirMs); }
                    catch (InterruptedException ie) {
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
        logDiagnosticos(odStats, graph, enrutador);

        SimulacionResponse res = construirRespuestaFront(0, tiempoMs,
                dataLoader.getVuelos(), bloques.size(), plan.get(0).scStart.toLocalDate());
        llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, totalVuelosCancelados, false, -1);
        llenarMetricasTa(res.getMetricas(), taStats, saMs);
        llenarMetricasBacklog(res.getMetricas(), backlog);
        res.setK(k); res.setSaMinutos(saMin);
        return res;
    }

    public SimulacionResponse.BloqueSimulacion getBloque(int index) {
        if (bloquesCacheados == null || index < 0 || index >= bloquesCacheados.size()) return null;
        return bloquesCacheados.get(index);
    }

    // =========================================================
    // Escenario 1: Día a día (ventana por ventana, con estado)
    // =========================================================

    /** Inicializa el estado del escenario 1. Debe llamarse antes de procesarSiguienteVentana(). */
    public synchronized Map<String, Object> inicializarEscenario1(double cancelProb) {
        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb));
        int k = props.getScenario().getKDefault1(); // K=1 día a día
        log.info("Escenario 1 — inicializando (K={}, cancelProb={}%) ...",
                k, String.format("%.1f", cancelProb * 100));

        sc1Graph     = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        sc1Enrutador = new GreedyRepairOperator(sc1Graph);
        sc1Dummy     = new AlnsSolution(Collections.emptyList());
        sc1Idx       = 0;
        sc1Envios    = sc1Enrutadas = sc1SinRuta = sc1CumpleSLA = sc1Tardadas = 0;
        sc1Maletas   = 0L;
        sc1TaStats   = new TaStats();
        sc1Backlog   = new BacklogManager(
                props.getBacklog().getMaxSize(),
                props.getBacklog().isPurgarVencidas());
        sc1Bloques   = new ArrayList<>();
        sc1OdStats.clear();

        sc1Plan = construirPlanBloques(k);

        if (cancelProb > 0.0 && !sc1Plan.isEmpty()) {
            long startDay = sc1Plan.get(0).scStart.toLocalDate().toEpochDay();
            long endDay   = sc1Plan.get(sc1Plan.size() - 1).scEnd.toLocalDate().toEpochDay() + 3;
            Set<Long> cancelados = FlightCancellationSimulator.generate(
                    sc1Graph.edges, startDay, endDay, cancelProb);
            sc1Enrutador.setCancelledFlights(cancelados);
            log.info("E1 listo: {} bloques, {} cancelaciones", sc1Plan.size(), cancelados.size());
        } else {
            log.info("E1 listo: {} bloques, sin cancelaciones", sc1Plan.size());
        }

        return Map.of(
            "estado",        "inicializado",
            "totalVentanas", sc1Plan.size(),
            "ventanaActual", 0
        );
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

        TemporalContext ctx = sc1Plan.get(sc1Idx);
        sc1Idx++;

        ResultadoVentana rv = procesarBloque(ctx, sc1Graph, sc1Enrutador, sc1Dummy, sc1OdStats, sc1Backlog);
        sc1Bloques.add(rv.bloque);
        sc1TaStats.acumular(ctx.taMs);

        sc1Envios    += rv.envios;
        sc1Enrutadas += rv.enrutadas;
        sc1SinRuta   += rv.sinRuta;
        sc1CumpleSLA += rv.cumpleSLA;
        sc1Tardadas  += rv.tardadas;
        sc1Maletas   += rv.maletas;

        // Propagar tasa sinRuta al siguiente bloque (para iteraciones dinámicas).
        if (sc1Idx < sc1Plan.size()) {
            double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
            sc1Plan.get(sc1Idx).tasaSinRutaPrevia = tasa;
        }

        log.info("E1 bloque {}/{}: envíos:{} | enrutados:{} | tardados:{} | sinRuta:{} | Ta={}ms | backlog={}",
                sc1Idx, sc1Plan.size(),
                rv.envios, rv.enrutadas, rv.tardadas, rv.sinRuta, ctx.taMs,
                sc1Backlog != null ? sc1Backlog.size() : 0);

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

    /** Devuelve el estado actual del escenario 1 sin avanzar la ventana. */
    public Map<String, Object> getEstadoEscenario1() {
        return Map.of(
            "inicializado",  sc1Graph != null,
            "ventanaActual", sc1Idx,
            "totalVentanas", sc1Plan != null ? sc1Plan.size() : 0,
            "totalEnvios",   sc1Envios,
            "totalEnrutadas",sc1Enrutadas,
            "totalSinRuta",  sc1SinRuta,
            "totalCumpleSLA",sc1CumpleSLA,
            "totalTardadas", sc1Tardadas,
            "totalMaletas",  sc1Maletas
        );
    }

    /** Devuelve un bloque ya procesado por índice (escenario 1). */
    public SimulacionResponse.BloqueSimulacion getBloqueEsc1(int index) {
        if (sc1Bloques == null || index < 0 || index >= sc1Bloques.size()) return null;
        return sc1Bloques.get(index);
    }

    // =========================================================
    // Escenario 3: Simulación hasta el colapso
    // =========================================================

    /**
     * Ejecuta el algoritmo con cancelaciones y se detiene cuando la tasa de
     * envíos sin ruta en una ventana supera umbralColapso.
     */
    public SimulacionResponse ejecutarHastaColapso(int k, double cancelProb, double umbralColapso) {
        return ejecutarHastaColapso(k, cancelProb, umbralColapso, null);
    }

    public SimulacionResponse ejecutarHastaColapso(int k, double cancelProb,
                                                    double umbralColapso, JobState job) {
        cancelProb    = Math.max(0.0, Math.min(1.0, cancelProb));
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        int saMin = props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);
        log.info("Escenario 3 — colapso (K={}, Sa={}min, Sc={}min, cancelProb={}%, umbral={}%, async={}) ...",
                k, saMin, scMin,
                String.format("%.1f", cancelProb * 100),
                String.format("%.1f", umbralColapso * 100),
                job != null);
        long inicio = System.currentTimeMillis();

        List<TemporalContext> plan = construirPlanBloques(k);
        if (plan.isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k); r.setSaMinutos(saMin);
            return r;
        }

        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AlnsSolution solucionDummy     = new AlnsSolution(Collections.emptyList());

        if (cancelProb > 0.0) {
            long startDay = plan.get(0).scStart.toLocalDate().toEpochDay();
            long endDay   = plan.get(plan.size() - 1).scEnd.toLocalDate().toEpochDay() + 3;
            Set<Long> cancelados = FlightCancellationSimulator.generate(
                    graph.edges, startDay, endDay, cancelProb);
            enrutador.setCancelledFlights(cancelados);
            log.info("E3 cancelaciones: {} vuelo-días", cancelados.size());
        }

        List<SimulacionResponse.BloqueSimulacion> bloques = new ArrayList<>();
        Map<String, int[]> odStats = new HashMap<>();
        int  totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
             totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        boolean collapsoDetectado = false;
        int     bloqueColapso     = -1;
        TaStats taStats = new TaStats();
        boolean simularTiempoReal = props.getScenario().isSimularTiempoReal3();
        long saMs = saMin * 60_000L;
        int  totalBloques = plan.size();
        BacklogManager backlog = new BacklogManager(
                props.getBacklog().getMaxSize(),
                props.getBacklog().isPurgarVencidas());
        int umbralBacklog = props.getScenario().getUmbralColapsoBacklog();

        for (TemporalContext ctx : plan) {
            bloqueActual++;
            ResultadoVentana rv = procesarBloque(ctx, graph, enrutador, solucionDummy, odStats, backlog);
            bloques.add(rv.bloque);
            taStats.acumular(ctx.taMs);

            totalEnvios    += rv.envios;
            totalEnrutadas += rv.enrutadas;
            totalSinRuta   += rv.sinRuta;
            totalCumpleSLA += rv.cumpleSLA;
            totalTardadas  += rv.tardadas;
            totalMaletas   += rv.maletas;

            // Reporte de progreso al job (si está siendo ejecutado de forma async)
            if (job != null) {
                job.bloqueActual = bloqueActual;
                job.totalBloques = totalBloques;
                job.taPromedioMs = taStats.promedio();
            }

            // Propagar tasa sinRuta al siguiente bloque para iteraciones dinámicas
            if (bloqueActual < plan.size()) {
                double tasa = rv.envios > 0 ? (double) rv.sinRuta / rv.envios : 0.0;
                plan.get(bloqueActual).tasaSinRutaPrevia = tasa;
            }

            // Detectar colapso: tasa de sinRuta de este bloque supera el umbral
            // (mínimo 5 envíos en el bloque para evitar falsos positivos)
            if (rv.envios >= 5 && (double) rv.sinRuta / rv.envios >= umbralColapso) {
                collapsoDetectado = true;
                bloqueColapso     = bloqueActual;
                log.warn("COLAPSO en bloque {} — sinRuta:{}/{} ({}%)",
                        bloqueActual, rv.sinRuta, rv.envios,
                        String.format("%.0f", rv.sinRuta * 100.0 / rv.envios));
                break;
            }

            // Detección alternativa: backlog descontrolado (saturación sostenida)
            if (umbralBacklog > 0 && backlog.size() >= umbralBacklog) {
                collapsoDetectado = true;
                bloqueColapso     = bloqueActual;
                log.warn("COLAPSO en bloque {} — backlog={} >= umbral={}",
                        bloqueActual, backlog.size(), umbralBacklog);
                break;
            }

            // Sleep para respetar el modelo Ta/Sa. En escenario 3 normalmente desactivado
            // (queremos llegar al colapso lo antes posible), pero soportado para inspección.
            if (simularTiempoReal && bloqueActual < totalBloques) {
                long dormirMs = saMs - ctx.taMs;
                if (dormirMs > 0) {
                    try { Thread.sleep(dormirMs); }
                    catch (InterruptedException ie) {
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
                collapsoDetectado ? "COLAPSÓ en bloque " + bloqueColapso : "sin colapso",
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
        res.setK(k); res.setSaMinutos(saMin);
        return res;
    }

    // =========================================================
    // ACO
    // =========================================================
    public SimulacionResponse ejecutarACO() {
        log.info("Ejecutando ACO...");
        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        List<LuggageBatch> batches = mapper.mapToBatches(dataLoader.getMaletasMuestra(100));
        batches.sort(Comparator.comparing(LuggageBatch::getReadyTime));

        ConfigACO config = new ConfigACO();
        config.antCount   = 20;
        config.iterations = 100;
        new AlgorithmACO(graph, config);

        return construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
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
        ctx.marcarInicio();

        // 1. Eje de datos: consumir [scStart, scEnd) → todo lo registrado en ese rango.
        List<Maleta>       maletasVentana = dataLoader.getMaletasEnRango(ctx.scStart, ctx.scEnd);
        List<LuggageBatch> bloqueBatches  = mapper.mapToBatches(maletasVentana);

        // 2. Backlog: purgar vencidas y traer pendientes de bloques anteriores.
        if (backlog != null) {
            backlog.purgarVencidas(ctx.scStart);
            int maxRepl  = props.getBacklog().getMaxReplanificacionesPorBloque();
            int maxTotal = backlog.size();
            // Política: traer todos los sinRuta + hasta {maxRepl} replanificables.
            // Como no hay distinción al hacer poll, limitamos por total cuando es razonable.
            int polled   = (maxRepl > 0 && maxRepl < maxTotal) ? maxRepl : maxTotal;
            List<LuggageBatch> pendientes = backlog.pollPendientes(polled);

            // Liberar capacidad global de los replanificables (ya commiteados).
            for (LuggageBatch b : pendientes) {
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

        // 3. Greedy + ALNS sobre el lote total (datos del bloque + pendientes).
        Map<Long, Integer> blockFlight  = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        List<LuggageBatch> intra = new ArrayList<>();
        List<LuggageBatch> inter = new ArrayList<>();
        for (LuggageBatch b : bloqueBatches) {
            if (b.getSlaLimitHours() <= 24) intra.add(b); else inter.add(b);
        }
        intra.forEach(b -> enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport));
        inter.forEach(b -> enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport));

        List<LuggageBatch> finalBatches;
        if (bloqueBatches.stream().anyMatch(b -> !b.isCumpleSLA())) {
            AlgorithmALNS alns = new AlgorithmALNS(
                    graph, enrutador, bloqueBatches, blockFlight, blockAirport, props);

            // Iteraciones dinámicas: si el bloque previo tuvo alta tasa de sinRuta,
            // dedicamos más cómputo (cerca del colapso) para intentar recuperar.
            double umbralCerca = 0.10;
            int iteraciones = (ctx.tasaSinRutaPrevia >= umbralCerca)
                    ? props.getAlns().getIteracionesCercaColapso()
                    : props.getAlns().getIteracionesBase();

            // Presupuesto de tiempo: 70% de Sa para mantener Ta < Sa siempre.
            long saMs = ctx.saMinutos * 60_000L;
            alns.tiempoLimiteMs = (long) (saMs * 0.7);

            alns.run(iteraciones);
            finalBatches = alns.getBestSolution().getBatches();
            enrutador.commitBlock(alns.getBestBlockFlight(), alns.getBestBlockAirport());
        } else {
            finalBatches = bloqueBatches;
            enrutador.commitBlock(blockFlight, blockAirport);
        }

        List<SimulacionResponse.AsignacionMaleta> asignaciones = buildAsignaciones(finalBatches);

        int enrutadas  = (int) asignaciones.stream().filter(SimulacionResponse.AsignacionMaleta::isEnrutada).count();
        int cumpleSLA  = (int) asignaciones.stream().filter(a -> a.isEnrutada() && a.isCumpleSLA()).count();
        int tardadas   = enrutadas - cumpleSLA;
        int sinRuta    = finalBatches.size() - enrutadas;
        long maletas   = finalBatches.stream().mapToLong(LuggageBatch::getQuantity).sum();

        for (SimulacionResponse.AsignacionMaleta a : asignaciones) {
            int[] s = odStats.computeIfAbsent(a.getOrigen() + "->" + a.getDestino(), key -> new int[2]);
            s[0]++;
            if (a.isEnrutada()) s[1]++;
        }

        // 4. Reabastecer el backlog con los batches que aún pendientes/críticos.
        if (backlog != null) {
            double umbralSlack = props.getBacklog().getUmbralReplanificacionSlack();
            for (LuggageBatch b : finalBatches) {
                boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
                if (!enrutada) {
                    backlog.addSinRuta(b);
                } else if (b.isCumpleSLA() && b.getSlaSlackRatio() < umbralSlack) {
                    // Próximo a tardar — candidato a replanificación preventiva.
                    backlog.addReplanificable(b);
                }
            }
        }

        ctx.marcarFin();

        SimulacionResponse.BloqueSimulacion bloque = new SimulacionResponse.BloqueSimulacion();
        bloque.setHoraInicio(ctx.scStart.toString());
        bloque.setHoraFin(ctx.scEnd.toString());
        bloque.setMaletasProcesadas(finalBatches.size());
        bloque.setMaletasEnrutadas(enrutadas);
        bloque.setAsignaciones(asignaciones);
        bloque.setBloqueIdx(ctx.bloqueIdx);
        bloque.setTaMs(ctx.taMs);
        bloque.setScMinutos(ctx.scMinutos);

        return new ResultadoVentana(bloque, finalBatches.size(), enrutadas, sinRuta, cumpleSLA, tardadas, maletas);
    }

    /**
     * Construye la lista de {@link TemporalContext} que cubre todo el dataset cargado.
     * Cada bloque cubre {@code Sc = K*Sa} minutos en el eje de datos.
     */
    private List<TemporalContext> construirPlanBloques(int k) {
        LocalDateTime primero = dataLoader.getPrimeraVentana();
        LocalDateTime ultimo  = dataLoader.getUltimaVentana();
        if (primero == null || ultimo == null) return Collections.emptyList();

        int saMin = props.getScenario().getSaMinutos();
        int scMin = Math.max(saMin, k * saMin);
        // Última ventana también contiene maletas → cubrir hasta ultimo + saMin.
        LocalDateTime fin = ultimo.plusMinutes(saMin);

        // ─── NUEVO: LIMITAR A UN PERÍODO MÁXIMO (EJ. 5 DÍAS) ──────────────
        // Leemos la propiedad del application.yaml (si no existe o es 0, procesa todo)
        int maxVentanas = props.getScenario().getMaxVentanas();
        if (maxVentanas > 0) {
            LocalDateTime fechaTope = primero.plusMinutes((long) maxVentanas * saMin);
            if (fin.isAfter(fechaTope)) {
                fin = fechaTope;
                log.info("Línea de tiempo acotada artificialmente a {} ventanas. Nuevo final de simulación: {}",
                        maxVentanas, fin);
            }
        }
        // ──────────────────────────────────────────────────────────────────

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

    /** Construye los DTOs de asignación para una lista de batches ya ruteados. */
    private List<SimulacionResponse.AsignacionMaleta> buildAsignaciones(List<LuggageBatch> batches) {
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

            List<SimulacionResponse.TramoRuta> tramos = Collections.emptyList();
            if (enrutada && b.getAssignedDepartures() != null && !b.getAssignedDepartures().isEmpty()) {
                var route = b.getAssignedRoute();
                var deps  = b.getAssignedDepartures();
                tramos    = new ArrayList<>();
                for (int ti = 0; ti < route.size(); ti++) {
                    var  edge   = route.get(ti);
                    long depMin = deps.get(ti);
                    long arrMin = depMin + edge.durationMinutes;
                    SimulacionResponse.TramoRuta tr = new SimulacionResponse.TramoRuta();
                    tr.setVueloId(edge.id);
                    tr.setOrigen(edge.from != null ? edge.from.code : "");
                    tr.setDestino(edge.to   != null ? edge.to.code   : "");
                    tr.setSalidaUtc(epochMinToUtc(depMin));
                    tr.setLlegadaUtc(epochMinToUtc(arrMin));
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
            if (sal == 0) { log.warn("  AISLADO: {}", code); sinSalida++; }
            else            log.info("  {} → {} vuelos", code, sal);
        }
        if (sinSalida == 0) log.info("  Todos los aeropuertos tienen salidas.");
        enrutador.logEstadisticasCapacidad();
        log.info("=======================");
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
            vb.setId(String.valueOf(v.getId()));
            vb.setOrigen(v.getOrigen());
            vb.setDestino(v.getDestino());
            vb.setFechaSalida(v.getFechaHoraSalida().plusDays(dayShift).toString());
            vb.setFechaLlegada(v.getFechaHoraLlegada().plusDays(dayShift).toString());
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

    /** Llena las métricas del backlog acumulativo en la respuesta. */
    private static void llenarMetricasBacklog(SimulacionResponse.Metricas m, BacklogManager backlog) {
        m.setBacklogActual(backlog.size());
        m.setBacklogPico(backlog.picoHistorico());
        m.setSinRutaDefinitivo(backlog.sinRutaDefinitivo());
    }

    private static String epochMinToUtc(long epochMin) {
        long epochDay    = epochMin / 1440L;
        int  minuteOfDay = (int)(epochMin % 1440L);
        return LocalDateTime.of(
                LocalDate.ofEpochDay(epochDay),
                LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        ).toString();
    }

    private void agregarInfoAeropuerto(Map<String, SimulacionResponse.AeropuertoDTO> map,
                                        String cod, Aeropuerto a) {
        if (!map.containsKey(cod)) {
            SimulacionResponse.AeropuertoDTO dto = new SimulacionResponse.AeropuertoDTO();
            dto.setCodigo(cod);
            dto.setLatitud(a.getLatitud());
            dto.setLongitud(a.getLongitud());
            map.put(cod, dto);
        }
    }

    // =========================================================
    // Clases internas de apoyo
    // =========================================================
    private record ResultadoVentana(
            SimulacionResponse.BloqueSimulacion bloque,
            int envios, int enrutadas, int sinRuta, int cumpleSLA, int tardadas, long maletas) {}

    /** Acumulador para estadísticas de Ta (tiempo de algoritmo) entre bloques. */
    private static final class TaStats {
        private long min = Long.MAX_VALUE;
        private long max = 0L;
        private long suma = 0L;
        private int  n = 0;

        void acumular(long taMs) {
            if (taMs < min) min = taMs;
            if (taMs > max) max = taMs;
            suma += taMs;
            n++;
        }

        long min()      { return n == 0 ? 0 : min; }
        long max()      { return max; }
        long suma()     { return suma; }
        long promedio() { return n == 0 ? 0 : suma / n; }
    }
}
