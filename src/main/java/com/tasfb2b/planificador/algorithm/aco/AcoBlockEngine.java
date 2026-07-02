package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator.RouteCandidate;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.algorithm.grafo.Edge;
import com.tasfb2b.planificador.algorithm.grafo.Graph;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

@Slf4j
@Component
public class AcoBlockEngine {

    private static final int MAX_ROUTE_CANDIDATES = 3;
    private static final int DECISION_FRONTIER = 16;
    private static final int REGRET_FRONTIER = 4;
    private static final int MAX_CACHE_PATHS_PER_KEY = 8;
    private static final long CACHE_BUCKET_MIN = 60L;
    private static final double EXPLORATION_RATE = 0.15;
    private static final double BASE_PHEROMONE_BOOST = 2.0;
    private static final double PHEROMONE_MIN = 0.10;
    private static final double PHEROMONE_MAX = 20.0;
    private static final double RESERVA_BASE = 0.15;              // colchón en vuelos para flexibles
    private static final double UMBRAL_CONGESTION_DEFER = 2.0;    // ruta "cara" en congestión
    private static final long   MARGEN_DEFER_MIN = 1440L;         // solo diferir si slack > 24h (urgentes nunca)
    private static final int    GROUP_ROUTE_CANDIDATES = 5;       // más candidatos por grupo → más diversidad de congestión
    private static final boolean ENABLE_J3_DEFER = false;

    private final PlanificadorProperties props;
    private int diagSeq = 0;   // N1: secuencia de bloques para throttlear el diagnóstico a INFO

    public AcoBlockEngine(PlanificadorProperties props) {
        this.props = props;
    }

    public int procesar(Graph graph,
                         GreedyRepairOperator enrutador,
                         List<LuggageBatch> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport) {
        return procesar(graph, enrutador, batches, blockFlight, blockAirport, null, 0L);
    }

    public int procesar(Graph graph,
                         GreedyRepairOperator enrutador,
                         List<LuggageBatch> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport,
                         Random rng) {
        return procesar(graph, enrutador, batches, blockFlight, blockAirport, rng, 0L);
    }

    public int procesar(Graph graph,
                         GreedyRepairOperator enrutador,
                         List<LuggageBatch> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport,
                         Random rng,
                         long tiempoLimiteMs) {
        if (batches == null || batches.isEmpty()) return 0;

        Random random = rng != null ? rng : new Random();
        ConfigACO cfg = configurar(batches.size());
        List<LuggageBatch> base = ordenarPorUrgencia(batches);

        Map<LuggageBatch, String> batchKeys = new IdentityHashMap<>(base.size() * 2);
        for (LuggageBatch b : base) batchKeys.put(b, batchKey(b));

        long inicio = System.nanoTime();
        long deadline = tiempoLimiteMs > 0
                ? inicio + tiempoLimiteMs * 1_000_000L
                : Long.MAX_VALUE;

        Map<String, Double> pheromones = new HashMap<>();
        RouteCandidateCache routeCache = new RouteCandidateCache();
        SearchStats stats = new SearchStats();
        SolucionBloque mejor = null;
        int sinMejora = 0;
        int solucionesEvaluadas = 0;

        Set<LuggageBatch> diferidos = Collections.newSetFromMap(new IdentityHashMap<>());
        SolucionBloque baseDeterministica = construirSolucionBase(
                enrutador, base, blockFlight, blockAirport, routeCache, stats, deadline, batchKeys, diferidos);
        if (baseDeterministica != null) {
            mejor = baseDeterministica;
            solucionesEvaluadas++;
            if (baseDeterministica.enrutados == base.size()) stats.solucionesCompletas++;
            depositar(pheromones, baseDeterministica, cfg.q * BASE_PHEROMONE_BOOST);
        }

        List<LuggageBatch> baseAnts = base;
        if (!diferidos.isEmpty()) {
            baseAnts = new ArrayList<>(base.size() - diferidos.size());
            for (LuggageBatch b : base) if (!diferidos.contains(b)) baseAnts.add(b);
        }

        for (int iter = 0; iter < cfg.iterations && System.nanoTime() < deadline; iter++) {
            boolean huboMejora = false;

            for (int ant = 0; ant < cfg.antCount && System.nanoTime() < deadline; ant++) {
                SolucionBloque candidata = construirSolucionHormiga(
                        enrutador, baseAnts, blockFlight, blockAirport, pheromones,
                        routeCache, stats, cfg, random, deadline, batchKeys);
                solucionesEvaluadas++;
                if (candidata.enrutados == base.size()) stats.solucionesCompletas++;
                if (mejorQue(candidata, mejor)) {
                    mejor = candidata;
                    huboMejora = true;
                }
            }

            evaporar(pheromones, cfg.evaporation);
            if (mejor != null) depositar(pheromones, mejor, cfg.q);

            sinMejora = huboMejora ? 0 : sinMejora + 1;
            if (cfg.maxNoImprovement > 0 && sinMejora >= cfg.maxNoImprovement) break;
        }

        for (LuggageBatch b : batches) {
            b.clearRoute();
            b.setCumpleSLA(false);
        }

        int enrutados = 0;
        int onTime = 0;
        if (mejor != null) {
            for (Asignacion asignacion : mejor.asignaciones) {
                if (!asignacion.route.isCumpleSLA()) continue;
                enrutador.aplicarCandidatoRuta(asignacion.batch, asignacion.route);
                enrutador.aplicarCandidatoBloque(asignacion.batch, asignacion.route, blockFlight, blockAirport);
                enrutados++;
                onTime++;
            }
        }

        long elapsedMs = (System.nanoTime() - inicio) / 1_000_000L;
        int sinRuta = batches.size() - enrutados;
        if (log.isInfoEnabled() && ++diagSeq % 50 == 0) {
            log.info("ACO padre bloque batches={} enrutados={} onTime={} sinRuta={} soluciones={} completas={} cacheHits={} cacheRejects={} dijkstraCalls={} t={}ms",
                    batches.size(), enrutados, onTime, sinRuta, solucionesEvaluadas, stats.solucionesCompletas,
                    stats.cacheHits, stats.cacheRejects, stats.dijkstraCalls, elapsedMs);
        } else if (log.isDebugEnabled()) {
            log.debug("ACO padre bloque batches={} enrutados={} onTime={} sinRuta={} soluciones={} completas={} cacheHits={} cacheRejects={} dijkstraCalls={} t={}ms",
                    batches.size(), enrutados, onTime, sinRuta, solucionesEvaluadas, stats.solucionesCompletas,
                    stats.cacheHits, stats.cacheRejects, stats.dijkstraCalls, elapsedMs);
        }
        return enrutados;
    }

    List<LuggageBatch> ordenarPorUrgencia(List<LuggageBatch> batches) {
        List<LuggageBatch> copia = new ArrayList<>(batches);
        copia.sort(Comparator
                .comparingLong(AcoBlockEngine::deadlineEpochMin)
                .thenComparing(Comparator.comparingInt(LuggageBatch::getQuantity).reversed())
                .thenComparing(LuggageBatch::getOriginCode)
                .thenComparing(LuggageBatch::getDestCode));
        return copia;
    }

    private static long deadlineEpochMin(LuggageBatch batch) {
        return GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime())
                + (long) batch.getSlaLimitHours() * 60L;
    }

    private ConfigACO configurar(int batchCount) {
        ConfigACO cfg = new ConfigACO();
        if (batchCount > 100) {
            cfg.antCount = 8;
            cfg.iterations = 54;
        } else if (batchCount > 30) {
            cfg.antCount = 10;
            cfg.iterations = 84;
        } else {
            cfg.antCount = 14;
            cfg.iterations = 108;
        }
        cfg.maxNoImprovement = 8;
        cfg.alpha = 1.0;
        cfg.beta = 2.0;
        cfg.evaporation = 0.30;
        cfg.q = 100.0;
        cfg.initialPheromone = 1.0;
        return cfg;
    }

    private SolucionBloque construirSolucionBase(GreedyRepairOperator enrutador,
                                                  List<LuggageBatch> base,
                                                  Map<Long, Integer> blockFlight,
                                                  Map<Long, Integer> blockAirport,
                                                  RouteCandidateCache routeCache,
                                                  SearchStats stats,
                                                  long deadline,
                                                  Map<LuggageBatch, String> batchKeys,
                                                  Set<LuggageBatch> diferidos) {
        Map<Long, Integer> simFlight = new HashMap<>(blockFlight);
        Map<Long, Integer> simAirport = new HashMap<>(blockAirport);
        List<Asignacion> asignaciones = new ArrayList<>();

        Map<String, List<LuggageBatch>> grupos = new LinkedHashMap<>();
        for (LuggageBatch batch : base) {
            grupos.computeIfAbsent(groupKey(batch), k -> new ArrayList<>()).add(batch);
        }

        for (List<LuggageBatch> grupo : grupos.values()) {
            if (System.nanoTime() >= deadline) break;

            LuggageBatch rep = grupo.get(0);
            for (LuggageBatch b : grupo) {
                if (b.getQuantity() > rep.getQuantity()) rep = b;
            }
            List<RouteCandidate> rutasGrupo = obtenerRutas(
                    enrutador, rep, simFlight, simAirport, routeCache, stats, GROUP_ROUTE_CANDIDATES);

            for (LuggageBatch batch : grupo) {
                if (System.nanoTime() >= deadline) break;

                RouteCandidate elegida = seleccionarRuta(enrutador, batch, rutasGrupo, simFlight, simAirport);
                if (elegida == null) {
                    // Ninguna ruta del grupo le sirve: recomputar para este envío.
                    List<RouteCandidate> propias = obtenerRutas(
                            enrutador, batch, simFlight, simAirport, routeCache, stats, GROUP_ROUTE_CANDIDATES);
                    elegida = seleccionarRuta(enrutador, batch, propias, simFlight, simAirport);
                }
                if (elegida == null) continue;   // sinRuta → se difiere al backlog

                if (ENABLE_J3_DEFER
                        && elegida.getSlackMin() > MARGEN_DEFER_MIN
                        && elegida.getScarcityCost() > UMBRAL_CONGESTION_DEFER) {
                    diferidos.add(batch);
                    continue;
                }

                String bKey = batchKeys.get(batch);
                enrutador.aplicarCandidatoBloque(batch, elegida, simFlight, simAirport);
                asignaciones.add(new Asignacion(batch, elegida, pheromoneKey(bKey, elegida), bKey));
            }
        }

        return new SolucionBloque(asignaciones, base.size());
    }

    private RouteCandidate seleccionarRuta(GreedyRepairOperator enrutador,
                                           LuggageBatch batch,
                                           List<RouteCandidate> candidatos,
                                           Map<Long, Integer> simFlight,
                                           Map<Long, Integer> simAirport) {
        double reservaAlmacen = props.getStorageAware().getReservaAlmacenBase();
        RouteCandidate best = mejorPorCosto(enrutador, batch, candidatos, simFlight, simAirport,
                RESERVA_BASE, reservaAlmacen);
        if (best == null && (RESERVA_BASE > 0.0 || reservaAlmacen > 0.0)) {
            best = mejorPorCosto(enrutador, batch, candidatos, simFlight, simAirport, 0.0, 0.0);
        }
        return best;
    }

    private RouteCandidate mejorPorCosto(GreedyRepairOperator enrutador,
                                         LuggageBatch batch,
                                         List<RouteCandidate> candidatos,
                                         Map<Long, Integer> simFlight,
                                         Map<Long, Integer> simAirport,
                                         double reservaBase,
                                         double reservaAlmacenBase) {
        RouteCandidate best = null;
        double bestCost = Double.MAX_VALUE;
        for (RouteCandidate r : candidatos) {
            if (!r.isCumpleSLA()) continue;                                  // F1
            if (!enrutador.rutaSirveParaBatch(r, batch, simFlight, simAirport,
                    reservaBase, reservaAlmacenBase)) continue;
            double c = costoSeleccion(batch, r);
            if (c < bestCost) { bestCost = c; best = r; }
        }
        return best;
    }

    private double costoSeleccion(LuggageBatch batch, RouteCandidate r) {
        double slaMin = Math.max(1.0, batch.getSlaLimitHours() * 60.0);
        double slackRatio = Math.max(0.0, Math.min(1.0, r.getSlackMin() / slaMin));
        return r.getScarcityCost() * slackRatio + r.getTransitMin() * 1e-4;
    }

    private String groupKey(LuggageBatch batch) {
        long readyBucket = batch.getReadyTime() == null
                ? 0L
                : GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime()) / CACHE_BUCKET_MIN;
        return batch.getOriginCode() + '|' + batch.getDestCode()
                + '|' + readyBucket + '|' + batch.getSlaLimitHours();
    }

    private SolucionBloque construirSolucionHormiga(GreedyRepairOperator enrutador,
                                                    List<LuggageBatch> base,
                                                    Map<Long, Integer> blockFlight,
                                                    Map<Long, Integer> blockAirport,
                                                    Map<String, Double> pheromones,
                                                    RouteCandidateCache routeCache,
                                                    SearchStats stats,
                                                    ConfigACO cfg,
                                                    Random random,
                                                    long deadline,
                                                    Map<LuggageBatch, String> batchKeys) {
        Map<Long, Integer> simFlight = new HashMap<>(blockFlight);
        Map<Long, Integer> simAirport = new HashMap<>(blockAirport);
        PendingPool pendientes = new PendingPool(base);
        List<Asignacion> asignaciones = new ArrayList<>();

        Map<LuggageBatch, BatchOption> evalCache = new IdentityHashMap<>(base.size() * 2);

        while (!pendientes.isEmpty() && System.nanoTime() < deadline) {
            List<BatchOption> opciones = evaluarFrontier(
                    enrutador, pendientes, simFlight, simAirport, pheromones,
                    routeCache, stats, cfg, random, batchKeys, evalCache);
            if (opciones.isEmpty()) {
                BatchRef ref = pendientes.first();
                if (ref == null) break;
                pendientes.remove(ref);
                continue;
            }

            BatchOption opcion = elegirBatch(opciones, pheromones, cfg, random);
            if (opcion == null) break;

            Decision elegida = elegirRuta(opcion, pheromones, cfg, random);
            if (elegida == null) {
                pendientes.remove(opcion.ref);
                evalCache.remove(opcion.ref.batch);
                continue;
            }

            enrutador.aplicarCandidatoBloque(elegida.batch, elegida.route, simFlight, simAirport);
            asignaciones.add(new Asignacion(elegida.batch, elegida.route, elegida.key, elegida.batchKey));
            pendientes.remove(opcion.ref);

            evalCache.remove(elegida.batch);
            Set<Long> tocadas = enrutador.clavesOcupadas(elegida.route, elegida.batch);
            if (!tocadas.isEmpty() && !evalCache.isEmpty()) {
                evalCache.values().removeIf(opt -> !Collections.disjoint(opt.occupiedKeys, tocadas));
            }
        }

        return new SolucionBloque(asignaciones, base.size());
    }

    private List<BatchOption> evaluarFrontier(GreedyRepairOperator enrutador,
                                              PendingPool pendientes,
                                              Map<Long, Integer> simFlight,
                                              Map<Long, Integer> simAirport,
                                              Map<String, Double> pheromones,
                                              RouteCandidateCache routeCache,
                                              SearchStats stats,
                                              ConfigACO cfg,
                                              Random random,
                                              Map<LuggageBatch, String> batchKeys,
                                              Map<LuggageBatch, BatchOption> evalCache) {
        List<BatchOption> opciones = new ArrayList<>();
        List<BatchRef> sinRuta = new ArrayList<>();
        for (BatchRef ref : pendientes.frontier(REGRET_FRONTIER, random)) {
            BatchOption cached = evalCache.get(ref.batch);
            if (cached != null) {
                opciones.add(cached);
                continue;
            }
            List<RouteCandidate> rutas = obtenerRutas(
                    enrutador, ref.batch, simFlight, simAirport, routeCache, stats, MAX_ROUTE_CANDIDATES);
            if (rutas.isEmpty()) {
                sinRuta.add(ref);
                continue;
            }
            int alternativasOnTime = 0;
            for (RouteCandidate r : rutas) {
                if (r.isCumpleSLA()) alternativasOnTime++;
            }
            double regret = calcularRegret(ref.batch, rutas, alternativasOnTime);
            double heuristic = batchHeuristic(ref.batch)
                    * (1.0 + heuristic(ref.batch, rutas.get(0), alternativasOnTime))
                    * (1.0 + Math.min(2.0, regret));
            String bKey = batchKeys.get(ref.batch);
            double weighted = weightBatch(bKey, heuristic, pheromones, cfg);
            Set<Long> occupiedKeys = clavesDeRutas(enrutador, ref.batch, rutas);
            BatchOption opt = new BatchOption(ref, rutas, alternativasOnTime, regret,
                    heuristic, weighted, bKey, occupiedKeys);
            evalCache.put(ref.batch, opt);
            opciones.add(opt);
        }
        for (BatchRef ref : sinRuta) {
            pendientes.remove(ref);
        }
        return opciones;
    }

    private Set<Long> clavesDeRutas(GreedyRepairOperator enrutador, LuggageBatch batch, List<RouteCandidate> rutas) {
        Set<Long> keys = new HashSet<>(rutas.size() * 6);
        for (RouteCandidate ruta : rutas) {
            keys.addAll(enrutador.clavesOcupadas(ruta, batch));
        }
        return keys;
    }

    private BatchOption elegirBatch(List<BatchOption> opciones,
                                    Map<String, Double> pheromones,
                                    ConfigACO cfg,
                                    Random random) {
        if (opciones.isEmpty()) return null;
        if (random.nextDouble() < EXPLORATION_RATE) {
            return opciones.get(random.nextInt(opciones.size()));
        }

        double total = 0.0;
        for (BatchOption opcion : opciones) {
            total += opcion.weight;
        }
        if (total <= 0.0) return opciones.get(0);

        double pick = random.nextDouble() * total;
        double acc = 0.0;
        for (BatchOption opcion : opciones) {
            acc += opcion.weight;
            if (acc >= pick) return opcion;
        }
        return opciones.get(opciones.size() - 1);
    }

    private Decision elegirRuta(BatchOption opcion,
                                Map<String, Double> pheromones,
                                ConfigACO cfg,
                                Random random) {
        List<Decision> decisiones = new ArrayList<>(opcion.rutas.size());
        for (RouteCandidate ruta : opcion.rutas) {
            decisiones.add(new Decision(opcion.ref.batch, ruta,
                    pheromoneKey(opcion.batchKey, ruta), opcion.batchKey,
                    heuristic(opcion.ref.batch, ruta, opcion.alternativasOnTime)));
        }
        return elegirDecision(decisiones, pheromones, cfg, random);
    }

    private Decision elegirDecision(List<Decision> decisiones,
                                    Map<String, Double> pheromones,
                                    ConfigACO cfg,
                                    Random random) {
        if (decisiones.isEmpty()) return null;
        if (random.nextDouble() < EXPLORATION_RATE) {
            return decisiones.get(random.nextInt(decisiones.size()));
        }

        double total = 0.0;
        for (Decision d : decisiones) {
            total += weight(d, pheromones, cfg);
        }
        if (total <= 0.0) {
            Decision best = null;
            for (Decision d : decisiones) {
                if (best == null || d.heuristic > best.heuristic) best = d;
            }
            return best;
        }

        double pick = random.nextDouble() * total;
        double acc = 0.0;
        for (Decision d : decisiones) {
            acc += weight(d, pheromones, cfg);
            if (acc >= pick) return d;
        }
        return decisiones.get(decisiones.size() - 1);
    }

    private double weightBatch(String batchKey,
                               double heuristic,
                               Map<String, Double> pheromones,
                               ConfigACO cfg) {
        double pheromone = pheromones.getOrDefault(batchKey, cfg.initialPheromone);
        double pher = cfg.alpha == 1.0 ? pheromone : Math.pow(pheromone, cfg.alpha);
        return pher * Math.pow(Math.max(heuristic, 0.000001), cfg.beta);
    }

    private double weight(Decision d, Map<String, Double> pheromones, ConfigACO cfg) {
        double pheromone = pheromones.getOrDefault(d.key, cfg.initialPheromone);
        double pher = cfg.alpha == 1.0 ? pheromone : Math.pow(pheromone, cfg.alpha);
        return pher * Math.pow(Math.max(d.heuristic, 0.000001), cfg.beta);
    }

    private double batchHeuristic(LuggageBatch batch) {
        double urgency = 1.0 / Math.max(1.0, batch.getSlaLimitHours());
        double volume = 1.0 + Math.log1p(Math.max(1, batch.getQuantity())) / 8.0;
        return urgency * volume;
    }

    private List<RouteCandidate> obtenerRutas(GreedyRepairOperator enrutador,
                                               LuggageBatch batch,
                                               Map<Long, Integer> simFlight,
                                               Map<Long, Integer> simAirport,
                                               RouteCandidateCache routeCache,
                                               SearchStats stats,
                                               int maxCandidatos) {
        List<RouteCandidate> rutas = new ArrayList<>(maxCandidatos);
        Set<String> firmas = new HashSet<>();

        List<List<Edge>> cachedPaths = routeCache.get(batch);
        if (cachedPaths != null && !cachedPaths.isEmpty()) {
            boolean hit = false;
            for (List<Edge> path : cachedPaths) {
                RouteCandidate candidate = enrutador.materializarRutaCandidata(batch, path, simFlight, simAirport);
                if (candidate == null) {
                    stats.cacheRejects++;
                    continue;
                }
                if (firmas.add(candidate.signature())) {
                    rutas.add(candidate);
                    hit = true;
                }
                if (rutas.size() >= maxCandidatos) break;
            }
            if (hit) stats.cacheHits++;
        }

        if (rutas.size() < maxCandidatos) {
            stats.dijkstraCalls++;
            List<RouteCandidate> generadas = enrutador.generarCandidatosRuta(
                    batch, simFlight, simAirport, maxCandidatos);
            routeCache.put(batch, generadas);
            for (RouteCandidate candidate : generadas) {
                if (firmas.add(candidate.signature())) {
                    rutas.add(candidate);
                }
            }
        }

        rutas.sort(this::compareBaseRoute);
        if (rutas.size() <= maxCandidatos) return rutas;
        return new ArrayList<>(rutas.subList(0, maxCandidatos));
    }

    private double calcularRegret(LuggageBatch batch, List<RouteCandidate> rutas, int alternativasOnTime) {
        if (rutas.isEmpty()) return 0.0;
        double best = routeDesirability(batch, rutas.get(0), alternativasOnTime);
        double second = rutas.size() > 1
                ? routeDesirability(batch, rutas.get(1), alternativasOnTime)
                : 0.0;
        double scarcity = alternativasOnTime <= 1 ? 1.0 : 1.0 / alternativasOnTime;
        return scarcity + Math.max(0.0, (best - second) / Math.max(1.0, best));
    }

    private double routeDesirability(LuggageBatch batch, RouteCandidate route, int alternativasOnTime) {
        return heuristic(batch, route, alternativasOnTime);
    }

    private double heuristic(LuggageBatch batch, RouteCandidate route, int alternativasOnTime) {
        double slaMin = Math.max(1.0, batch.getSlaLimitHours() * 60.0);
        double slackRatio = Math.max(0.0, Math.min(1.0, route.getSlackMin() / slaMin));
        // J1: NO premiar velocidad/holgura (eso desperdiciaba capacidad escasa). En su
        // lugar, premiar rutas de baja congestión, y MÁS cuanto más holgado el envío.
        double slaScore = route.isCumpleSLA()
                ? 4.0
                : 0.05 / (1.0 + Math.max(0L, -route.getSlackMin()) / 60.0);
        double scarcityAlt = 1.0 + 1.0 / Math.max(1, alternativasOnTime);
        double congestion = 1.0 / (1.0 + route.getScarcityCost() * (0.5 + slackRatio));
        double capacityScore = 1.0 / (1.0 + Math.max(0.0, route.getPressure()) * 8.0);
        double routeShape = 1.0 / (1.0 + Math.max(0, route.getLegs() - 1) * 0.35);
        double urgency = 1.0 / Math.max(1.0, batch.getSlaLimitHours());
        return slaScore * scarcityAlt * congestion * capacityScore * routeShape + urgency;
    }

    private int compareBaseRoute(RouteCandidate a, RouteCandidate b) {
        int c = Boolean.compare(b.isCumpleSLA(), a.isCumpleSLA());
        if (c != 0) return c;
        c = Long.compare(Math.max(0L, -a.getSlackMin()), Math.max(0L, -b.getSlackMin()));
        if (c != 0) return c;
        c = Double.compare(a.getScarcityCost(), b.getScarcityCost());
        if (c != 0) return c;
        c = Double.compare(a.getPressure(), b.getPressure());
        if (c != 0) return c;
        c = Long.compare(a.getArrivalMin(), b.getArrivalMin());
        if (c != 0) return c;
        c = Integer.compare(a.getLegs(), b.getLegs());
        if (c != 0) return c;
        return Long.compare(b.getSlackMin(), a.getSlackMin());
    }

    private boolean mejorQue(SolucionBloque candidata, SolucionBloque actual) {
        if (candidata == null) return false;
        if (actual == null) return true;
        if (candidata.cumpleSla != actual.cumpleSla) {
            return candidata.cumpleSla > actual.cumpleSla;
        }
        if (candidata.enrutados != actual.enrutados) {
            return candidata.enrutados > actual.enrutados;
        }
        if (candidata.tardados != actual.tardados) {
            return candidata.tardados < actual.tardados;
        }
        return candidata.cost < actual.cost;
    }

    private void evaporar(Map<String, Double> pheromones, double evaporation) {
        if (pheromones.isEmpty()) return;
        Iterator<Map.Entry<String, Double>> it = pheromones.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Double> entry = it.next();
            double next = entry.getValue() * (1.0 - evaporation);
            if (next < PHEROMONE_MIN) {
                entry.setValue(PHEROMONE_MIN);
            } else {
                entry.setValue(next);
            }
        }
    }

    private void depositar(Map<String, Double> pheromones, SolucionBloque solucion, double q) {
        double delta = q / (1.0 + Math.max(0.0, solucion.cost));
        for (Asignacion a : solucion.asignaciones) {
            pheromones.merge(a.key, delta, (oldValue, add) -> Math.min(PHEROMONE_MAX, oldValue + add));
            pheromones.merge(a.batchKey, delta, (oldValue, add) -> Math.min(PHEROMONE_MAX, oldValue + add));
        }
    }

    private String batchKey(LuggageBatch batch) {
        return "B|"
                + batch.getOriginCode() + '|'
                + batch.getDestCode() + '|'
                + batch.getReadyTime() + '|'
                + batch.getQuantity() + '|'
                + batch.getSlaLimitHours();
    }

    private String pheromoneKey(String batchKey, RouteCandidate route) {
        return batchKey + '#' + route.signature();
    }

    private static final class PendingPool {
        private final List<LuggageBatch> items;

        PendingPool(List<LuggageBatch> source) {
            this.items = new ArrayList<>(source);
        }

        boolean isEmpty() {
            return items.isEmpty();
        }

        BatchRef first() {
            return items.isEmpty() ? null : new BatchRef(items.get(0), 0);
        }

        List<BatchRef> frontier(int limit, Random random) {
            if (items.isEmpty()) return List.of();
            int n = Math.min(Math.max(1, limit), items.size());
            List<BatchRef> refs = new ArrayList<>(n + 1);
            for (int i = 0; i < n; i++) {
                refs.add(new BatchRef(items.get(i), i));
            }
            if (items.size() > DECISION_FRONTIER) {
                int idx = DECISION_FRONTIER + random.nextInt(items.size() - DECISION_FRONTIER);
                refs.add(new BatchRef(items.get(idx), idx));
            } else if (items.size() > n) {
                int idx = n + random.nextInt(items.size() - n);
                refs.add(new BatchRef(items.get(idx), idx));
            }
            return refs;
        }

        void remove(BatchRef ref) {
            if (ref == null || items.isEmpty()) return;
            if (ref.index >= 0 && ref.index < items.size() && items.get(ref.index) == ref.batch) {
                removeAt(ref.index);
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) == ref.batch) {
                    removeAt(i);
                    return;
                }
            }
        }

        private void removeAt(int index) {
            int last = items.size() - 1;
            if (index != last) {
                items.set(index, items.get(last));
            }
            items.remove(last);
        }
    }

    private static final class BatchRef {
        final LuggageBatch batch;
        final int index;

        BatchRef(LuggageBatch batch, int index) {
            this.batch = batch;
            this.index = index;
        }
    }

    private static final class BatchOption {
        final BatchRef ref;
        final List<RouteCandidate> rutas;
        final int alternativasOnTime;
        final double regret;
        final double heuristic;
        final double weight;
        final String batchKey;
        final Set<Long> occupiedKeys;

        BatchOption(BatchRef ref,
                    List<RouteCandidate> rutas,
                    int alternativasOnTime,
                    double regret,
                    double heuristic,
                    double weight,
                    String batchKey,
                    Set<Long> occupiedKeys) {
            this.ref = ref;
            this.rutas = rutas;
            this.alternativasOnTime = alternativasOnTime;
            this.regret = regret;
            this.heuristic = heuristic;
            this.weight = weight;
            this.batchKey = batchKey;
            this.occupiedKeys = occupiedKeys;
        }
    }

    private static final class RouteCandidateCache {
        private final Map<CacheKey, CachedPaths> cache = new HashMap<>();

        List<List<Edge>> get(LuggageBatch batch) {
            CacheKey key = CacheKey.from(batch);
            if (key == null) return List.of();
            CachedPaths entry = cache.get(key);
            return entry == null ? List.of() : entry.paths;
        }

        void put(LuggageBatch batch, List<RouteCandidate> candidates) {
            if (candidates == null || candidates.isEmpty()) return;
            CacheKey key = CacheKey.from(batch);
            if (key == null) return;
            // El Set de firmas se mantiene junto a los paths: no se recomputa en
            // cada put.
            CachedPaths entry = cache.computeIfAbsent(key, k -> new CachedPaths());
            for (RouteCandidate candidate : candidates) {
                if (candidate.getEdges().isEmpty()) continue;
                String signature = pathSignature(candidate.getEdges());
                if (!entry.signatures.add(signature)) continue;
                entry.paths.add(List.copyOf(candidate.getEdges()));
                if (entry.paths.size() >= MAX_CACHE_PATHS_PER_KEY) break;
            }
        }

        private static String pathSignature(List<Edge> path) {
            StringBuilder sb = new StringBuilder(path.size() * 6);
            for (Edge edge : path) {
                sb.append(edge.idx).append(';');
            }
            return sb.toString();
        }
    }

    private static final class CachedPaths {
        final List<List<Edge>> paths = new ArrayList<>();
        final Set<String> signatures = new HashSet<>();
    }

    private static final class CacheKey {
        final String origin;
        final String dest;
        final long readyBucket;
        final int slaHours;
        final int quantity;

        private CacheKey(String origin, String dest, long readyBucket, int slaHours, int quantity) {
            this.origin = origin;
            this.dest = dest;
            this.readyBucket = readyBucket;
            this.slaHours = slaHours;
            this.quantity = quantity;
        }

        static CacheKey from(LuggageBatch batch) {
            if (batch == null || batch.getReadyTime() == null) return null;
            long readyBucket = GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime()) / CACHE_BUCKET_MIN;
            return new CacheKey(batch.getOriginCode(), batch.getDestCode(), readyBucket,
                    batch.getSlaLimitHours(), batch.getQuantity());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey other)) return false;
            return readyBucket == other.readyBucket
                    && slaHours == other.slaHours
                    && quantity == other.quantity
                    && Objects.equals(origin, other.origin)
                    && Objects.equals(dest, other.dest);
        }

        @Override
        public int hashCode() {
            return Objects.hash(origin, dest, readyBucket, slaHours, quantity);
        }
    }

    private static final class SearchStats {
        int dijkstraCalls;
        int cacheHits;
        int cacheRejects;
        int solucionesCompletas;
    }

    private static final class Decision {
        final LuggageBatch batch;
        final RouteCandidate route;
        final String key;
        final String batchKey;
        final double heuristic;

        Decision(LuggageBatch batch, RouteCandidate route, String key, String batchKey, double heuristic) {
            this.batch = batch;
            this.route = route;
            this.key = key;
            this.batchKey = batchKey;
            this.heuristic = heuristic;
        }
    }

    private static final class Asignacion {
        final LuggageBatch batch;
        final RouteCandidate route;
        final String key;
        final String batchKey;

        Asignacion(LuggageBatch batch, RouteCandidate route, String key, String batchKey) {
            this.batch = batch;
            this.route = route;
            this.key = key;
            this.batchKey = batchKey;
        }
    }

    private static final class SolucionBloque {
        final List<Asignacion> asignaciones;
        final int enrutados;
        final int cumpleSla;
        final int tardados;
        final int sinRuta;
        final double cost;

        SolucionBloque(List<Asignacion> asignaciones, int totalBatches) {
            this.asignaciones = List.copyOf(asignaciones);
            this.enrutados = asignaciones.size();
            int ok = 0;
            double totalCost = 0.0;
            for (Asignacion a : asignaciones) {
                RouteCandidate r = a.route;
                if (r.isCumpleSLA()) ok++;
                long late = Math.max(0L, -r.getSlackMin());
                totalCost += r.getTransitMin()
                        + late * 10_000.0
                        + r.getPressure() * 500.0
                        + Math.max(0, r.getLegs() - 1) * 30.0;
            }
            this.cumpleSla = ok;
            this.tardados = enrutados - ok;
            this.sinRuta = totalBatches - enrutados;
            this.cost = totalCost + sinRuta * 1_000_000.0;
        }
    }
}
