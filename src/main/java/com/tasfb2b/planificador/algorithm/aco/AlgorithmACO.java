package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Algoritmo ACO base. Tiene DOS modos de operación según se inyecte o no
 * un {@link AcoRouteEvaluator} con {@link #setRouteEvaluator(AcoRouteEvaluator)}:
 *
 * <h3>MODO PRODUCCIÓN (con evaluator)</h3>
 * Usado por {@link AcoBlockEngine} en el endpoint principal de planificación
 * (motor=aco). El evaluator delega capacidad/almacén/SLA en el
 * {@code GreedyRepairOperator} de ALNS, compartiendo {@code blockFlight} y
 * {@code blockAirport}. Cumple las restricciones duras del modelo Sa/Sc/K
 * (flight-day, airport-day, overnight, horizonte 3d, conexión 10min, SLA por batch).
 * <b>Esta es la vía que recibe llamadas desde el front.</b>
 *
 * <h3>MODO PRUEBAS / DIAGNÓSTICO (sin evaluator)</h3>
 * Usado por {@code PlanificadorService.intentarPlanificarEnvio} (flujo
 * {@code ejecutarHastaColapso} y tests {@code Diagnostico*}). Usa
 * {@link Edge#usedCapacity} y {@link Node#storeLoad} como contadores globales
 * mutables — NO respeta el modelo flight-day/airport-day. Sus métricas no son
 * directamente comparables con las de producción. <b>No usar para resultados
 * que vayan al cliente.</b>
 */
public class AlgorithmACO {

    private final Graph graph;
    private final ConfigACO config;
    private CostFunction.EnvioContext envioContext;
    private AcoRouteEvaluator routeEvaluator;
    private static final int TOP_K_CANDIDATES = 8;
    private static final double GOOD_ENOUGH_COST = 360.0;

    // Hot path: feromonas locales del modo PRODUCCIÓN se mantienen en un double[]
    // indexado por edge.idx en vez de Map<Edge,Double>, evitando hashing/boxing.
    // dirtyList registra los idx que se han tocado, así evaporación y clamping
    // solo iteran sobre lo modificado y el reset entre corridas es O(dirtyCount).
    private double[] localPheromonesByIdx;
    private boolean[] localDirtyFlag;
    private int[] localDirtyList;
    private int localDirtyCount;

    private final List<Ant> ants = new ArrayList<>();
    private final Ant mejorGlobalReused = new Ant();
    private final Ant heuristicaAnt = new Ant();
    private Ant mejorGlobal = null;
    private double mejorCostoGlobal = Double.MAX_VALUE;
    private Random rng = new Random();
    private long deadlineNano = Long.MAX_VALUE;

    // Buffer reutilizable para candidatos en buildSolution/buscarRutaGreedy.
    // Evita ArrayList<Candidate> y new Candidate(...) en el inner loop.
    private Candidate[] candidates = new Candidate[32];
    private int candidatesCount;
    // Buffer reutilizable para la secuencia de Transition de la hormiga actual.
    // Se limpia al inicio de buildSolution/buscarRutaGreedy y se descarta tras
    // pasarlo a routeCost; no se comparte entre hormigas concurrentes (ACO es
    // secuencial por construcción dentro de cada run).
    private final ArrayList<AcoRouteEvaluator.Transition> transitionsBuffer = new ArrayList<>();

    public AlgorithmACO(Graph graph, ConfigACO config, CostFunction.EnvioContext envioContext) {
        this.graph = graph;
        this.config = config;
        this.envioContext = envioContext;

        for (int i = 0; i < config.antCount; i++) {
            ants.add(new Ant());
        }
        prepararArrayFeromonas();
    }

    public void setRandom(Random rng) {
        if (rng != null) this.rng = rng;
    }

    public void setRouteEvaluator(AcoRouteEvaluator routeEvaluator) {
        this.routeEvaluator = routeEvaluator;
    }

    public void setDeadlineNano(long deadlineNano) {
        this.deadlineNano = deadlineNano > 0 ? deadlineNano : Long.MAX_VALUE;
    }

    /**
     * Permite reutilizar una instancia de {@code AlgorithmACO} entre batches sin
     * reasignar hormigas, buffers, ni el array de feromonas. Llamar antes de
     * {@link #run(String, String)} con el contexto del nuevo batch.
     */
    public void setEnvioContext(CostFunction.EnvioContext envioContext) {
        this.envioContext = envioContext;
    }

    public void run(String start, String end) {
        Node startNode = graph.nodes.get(start);
        Node endNode = graph.nodes.get(end);
        if (startNode == null || endNode == null) return;
        if (timeExpired()) return;

        // Reset entre corridas para que la instancia pueda ser reusada (ver AcoBlockEngine).
        mejorGlobal = null;
        mejorCostoGlobal = Double.MAX_VALUE;
        resetLocalPheromones();

        if (buscarMejorDirecto(startNode, endNode)) return;
        if (timeExpired()) return;

        if (routeEvaluator == null) {
            inicializarFeromonas();
            precomputarHeuristicas();
        }

        int sinMejora = 0;

        if (routeEvaluator != null) {
            seedGreedySolutions(startNode, endNode);
            if (mejorGlobal != null && isGoodEnough(mejorGlobal)) return;
        }

        for (int it = 0; it < config.iterations; it++) {
            if (timeExpired()) break;
            boolean huboMejora = false;

            for (int antIdx = 0; antIdx < ants.size(); antIdx++) {
                if (timeExpired()) break;
                Ant ant = ants.get(antIdx);
                ant.reset();
                boolean useTopK = routeEvaluator != null && antIdx < ants.size() - 1;
                buildSolution(ant, startNode, endNode, useTopK);

                if (cumpleRestricciones(ant) && ant.totalCost < mejorCostoGlobal) {
                    mejorCostoGlobal = ant.totalCost;
                    actualizarMejorGlobal(ant);
                    huboMejora = true;
                    if (routeEvaluator != null && isGoodEnough(mejorGlobal)) return;
                }
            }

            updatePheromones();

            if (config.maxNoImprovement > 0) {
                sinMejora = huboMejora ? 0 : sinMejora + 1;
                if (sinMejora >= config.maxNoImprovement) break;
            }
        }

        Ant mejor = getMejorAnt();
        if (mejor != null && !mejor.path.isEmpty()) return;
        if (timeExpired()) return;

        buscarRutaGreedy(startNode, endNode, GreedyStrategy.BEST_DESIRABILITY);

        Ant mejorPostGreedy = getMejorAnt();
        if (mejorPostGreedy != null
                && cumpleRestricciones(mejorPostGreedy)
                && mejorPostGreedy.totalCost < mejorCostoGlobal) {
            mejorCostoGlobal = mejorPostGreedy.totalCost;
            actualizarMejorGlobal(mejorPostGreedy);
        }
    }

    private boolean buscarMejorDirecto(Node startNode, Node endNode) {
        Edge mejorEdge = null;
        double mejorCosto = Double.MAX_VALUE;
        boolean mejorOnTime = false;

        for (Edge e : graph.getEdgesFrom(startNode.code)) {
            if (timeExpired()) break;
            if (!e.to.equals(endNode)) continue;

            double costo;
            if (routeEvaluator != null) {
                AcoRouteEvaluator.Transition t = routeEvaluator.evaluate(e, routeEvaluator.initialReadyMin(), 0);
                if (t == null) continue;
                List<Edge> route = List.of(e);
                List<AcoRouteEvaluator.Transition> transitions = List.of(t);
                costo = routeEvaluator.routeCost(route, transitions);
                boolean onTime = routeEvaluator.isCompleteRouteOnTime(route, transitions);
                if (onTime && !mejorOnTime) {
                    mejorCosto = Double.MAX_VALUE;
                    mejorOnTime = true;
                } else if (onTime && costo < mejorCosto) {
                    mejorOnTime = true;
                } else if (!onTime && mejorOnTime) {
                    continue;
                }
            } else {
                if (!e.hasCapacity(envioContext.cantidadMaletas)) continue;
                if (!e.to.hasStorageCapacity(envioContext.cantidadMaletas)) continue;
                costo = e.cost;
            }

            if (costo < mejorCosto) {
                mejorCosto = costo;
                mejorEdge = e;
            }
        }

        if (mejorEdge == null) return false;

        Ant dst = mejorGlobalReused;
        dst.reset();
        dst.addNode(startNode);
        dst.addNode(endNode);
        dst.edgesPath.add(mejorEdge);
        dst.totalCost = mejorCosto;
        mejorGlobal = dst;
        mejorCostoGlobal = mejorCosto;
        return routeEvaluator == null || mejorOnTime;
    }

    private void seedGreedySolutions(Node start, Node end) {
        buscarRutaGreedy(start, end, GreedyStrategy.EARLIEST_ARRIVAL);
        buscarRutaGreedy(start, end, GreedyStrategy.LOWEST_COST);
        buscarRutaGreedy(start, end, GreedyStrategy.BEST_DESIRABILITY);
    }

    private void buscarRutaGreedy(Node start, Node end, GreedyStrategy strategy) {
        start.storeLoad(envioContext.cantidadMaletas);

        Node current = start;
        Ant ant = ants.get(0);
        ant.reset();
        ant.addNode(current);
        Edge lastEdge = null;
        long currentArrivalMin = routeEvaluator != null ? routeEvaluator.initialReadyMin() : 0L;
        List<AcoRouteEvaluator.Transition> transitions = transitionsBuffer;
        transitions.clear();

        while (!current.equals(end)) {
            if (timeExpired()) break;
            Edge mejor = null;
            double mejorValor = -1.0;
            AcoRouteEvaluator.Transition mejorTransition = null;

            for (Edge e : graph.getEdgesFrom(current.code)) {
                if (ant.visited(e.to)) continue;

                AcoRouteEvaluator.Transition transition = null;
                if (routeEvaluator != null) {
                    transition = routeEvaluator.evaluate(e, currentArrivalMin, ant.edgesPath.size());
                    if (transition == null) continue;
                } else {
                    if (!e.hasCapacity(envioContext.cantidadMaletas)) continue;
                    if (!e.to.hasStorageCapacity(envioContext.cantidadMaletas)) continue;
                    if (lastEdge != null && !CostFunction.tieneTiempoMinimoEscala(lastEdge, e)) continue;
                }

                double valor = greedyValue(e, transition, strategy, ant);
                if (valor > mejorValor) {
                    mejorValor = valor;
                    mejor = e;
                    mejorTransition = transition;
                }
            }

            if (mejor == null) break;

            current.releaseLoad(envioContext.cantidadMaletas);
            mejor.to.storeLoad(envioContext.cantidadMaletas);

            ant.edgesPath.add(mejor);
            if (mejorTransition != null) {
                transitions.add(mejorTransition);
                currentArrivalMin = mejorTransition.arrivalMin;
            }
            ant.addNode(mejor.to);
            current = mejor.to;

            if (current.equals(end)) {
                current.releaseLoad(envioContext.cantidadMaletas);
                break;
            }

            lastEdge = mejor;
        }

        if (!current.equals(end)) {
            current.releaseLoad(envioContext.cantidadMaletas);
        }

        if (current.equals(end) && !ant.edgesPath.isEmpty()
                && (routeEvaluator == null || routeEvaluator.isCompleteRouteFeasible(ant.edgesPath, transitions))) {
            ant.totalCost = routeEvaluator != null
                    ? routeEvaluator.routeCost(ant.edgesPath, transitions)
                    : CostFunction.calcularCostoRuta(ant, graph.edges, ant.edgesPath, envioContext);
            if (cumpleRestricciones(ant) && ant.totalCost < mejorCostoGlobal) {
                mejorCostoGlobal = ant.totalCost;
                actualizarMejorGlobal(ant);
            }
        }
    }

    public Ant getMejorAnt() {
        if (mejorGlobal != null) return mejorGlobal;

        Ant mejor = null;
        double mejorCosto = Double.MAX_VALUE;

        for (Ant ant : ants) {
            boolean llegaDestino = !ant.path.isEmpty()
                    && ant.path.get(ant.path.size() - 1).code.equals(envioContext.destinoICAO);
            if (llegaDestino && ant.totalCost < mejorCosto) {
                mejorCosto = ant.totalCost;
                mejor = ant;
            }
        }

        return mejor;
    }

    private void buildSolution(Ant ant, Node start, Node end, boolean useTopK) {
        start.storeLoad(envioContext.cantidadMaletas);

        Node current = start;
        ant.addNode(current);
        Edge lastEdge = null;
        long currentArrivalMin = routeEvaluator != null ? routeEvaluator.initialReadyMin() : 0L;
        List<AcoRouteEvaluator.Transition> transitions = transitionsBuffer;
        transitions.clear();
        int maxEscalas = 10;
        int escalas = 0;
        boolean llego = false;

        while (!current.equals(end)) {
            if (timeExpired()) break;
            List<Edge> options = graph.getEdgesFrom(current.code);
            if (options.isEmpty()) break;

            candidatesCount = 0;
            int optSize = options.size();
            int demanda = envioContext.cantidadMaletas;
            for (int i = 0; i < optSize; i++) {
                Edge e = options.get(i);
                if (ant.visited(e.to)) continue;
                // Pre-filter por capacidad física del vuelo: ahorra el cache
                // lookup del evaluator y la verificación detallada de capacidad
                // cuando la demanda nominal ya excede la capacidad del avión.
                if (e.capacity > 0 && e.capacity < demanda) continue;

                AcoRouteEvaluator.Transition transition = null;
                if (routeEvaluator != null) {
                    transition = routeEvaluator.evaluate(e, currentArrivalMin, ant.edgesPath.size());
                    if (transition == null) continue;
                } else {
                    if (!e.hasCapacity(demanda)) continue;
                    if (!e.to.hasStorageCapacity(demanda)) continue;
                    if (lastEdge != null && !CostFunction.tieneTiempoMinimoEscala(lastEdge, e)) continue;
                }
                addCandidate(e, transition);
            }

            if (candidatesCount == 0 || escalas >= maxEscalas) break;
            if (useTopK && candidatesCount > TOP_K_CANDIDATES) {
                // Sort in-place sobre el buffer; sin alocar sublistas ni copias.
                Arrays.sort(candidates, 0, candidatesCount,
                        (a, b) -> Double.compare(weightOf(b), weightOf(a)));
                candidatesCount = TOP_K_CANDIDATES;
            }

            Candidate chosen = selectEdge(candidatesCount);
            if (chosen == null) break;

            current.releaseLoad(envioContext.cantidadMaletas);
            chosen.edge.to.storeLoad(envioContext.cantidadMaletas);

            ant.edgesPath.add(chosen.edge);
            if (chosen.transition != null) {
                transitions.add(chosen.transition);
                currentArrivalMin = chosen.transition.arrivalMin;
            }
            ant.addNode(chosen.edge.to);
            current = chosen.edge.to;

            if (current.equals(end)) {
                llego = true;
                break;
            }

            lastEdge = chosen.edge;
            escalas++;

            // La hormiga actualiza su reloj al aterrizar
            ant.horaLlegadaActual = chosen.edge.arrivalTime;
        }

        if (llego) {
            current.releaseLoad(envioContext.cantidadMaletas);
        } else {
            for (Node n : ant.path) {
                n.releaseLoad(envioContext.cantidadMaletas);
            }
        }

        if (llego && routeEvaluator != null) {
            ant.totalCost = routeEvaluator.routeCost(ant.edgesPath, transitions);
        } else {
            ant.totalCost = CostFunction.calcularCostoRuta(ant, graph.edges, ant.edgesPath, envioContext);
        }
    }

    private void addCandidate(Edge edge, AcoRouteEvaluator.Transition transition) {
        if (candidatesCount == candidates.length) {
            candidates = Arrays.copyOf(candidates, candidates.length * 2);
        }
        Candidate slot = candidates[candidatesCount];
        if (slot == null) {
            slot = new Candidate();
            candidates[candidatesCount] = slot;
        }
        slot.edge = edge;
        slot.transition = transition;
        candidatesCount++;
    }


    // SELECCIÓN PROBABILÍSTICA
    // Hot path: sin HashMap, sin Math.pow, una sola pasada con array temporal.
    // Heurística^β ya viene cacheada en edge.heuristicCache (precomputarHeuristicas).
    // Para α==1.0 (config por defecto y único uso actual) se evita pow sobre la feromona.
    // SELECCIÓN PROBABILÍSTICA DINÁMICA
    private Candidate selectEdge(int n) {
        if (n == 0) return null;

        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += weightOf(candidates[i]);
        }

        if (sum <= 0.0) return null;

        double rand = rng.nextDouble() * sum;
        double acc = 0.0;
        for (int i = 0; i < n; i++) {
            acc += weightOf(candidates[i]);
            if (acc >= rand) return candidates[i];
        }
        return candidates[n - 1];
    }

    private double weightOf(Candidate c) {
        double pheromone = getPheromone(c.edge);
        double pher = config.alpha == 1.0 ? pheromone : Math.pow(pheromone, config.alpha);
        double heuristic = c.transition != null ? c.transition.desirability : c.edge.heuristicCache;
        return pher * heuristic;
    }

    private void precomputarHeuristicas() {
        boolean betaDos = config.beta == 2.0;
        for (Edge e : graph.edges) {
            double h = CostFunction.heuristica(e, envioContext, heuristicaAnt);
            e.heuristicCache = betaDos ? (h * h) : Math.pow(h, config.beta);
        }
    }

    private void updatePheromones() {
        double tauMax = 10.0;
        double tauMin = 0.5;
        if (routeEvaluator == null) {
            for (Edge e : graph.edges) {
                e.pheromone *= (1 - config.evaporation);
            }
        } else {
            // Evaporación solo sobre los edges tocados por hormigas en esta corrida.
            for (int i = 0; i < localDirtyCount; i++) {
                localPheromonesByIdx[localDirtyList[i]] *= (1 - config.evaporation);
            }
        }

        for (Ant ant : ants) {
            if (!cumpleRestricciones(ant)) continue;

            double deltaTau = config.q / (ant.totalCost + 1.0);
            for (Edge edge : ant.edgesPath) {
                addPheromone(edge, deltaTau);
            }
        }

        // 3. LÍMITES MAX-MIN: Evita la convergencia prematura
        if (routeEvaluator == null) {
            for (Edge e : graph.edges) {
                if (e.pheromone > tauMax) e.pheromone = tauMax;
                else if (e.pheromone < tauMin) e.pheromone = tauMin;
            }
        } else {
            for (int i = 0; i < localDirtyCount; i++) {
                int idx = localDirtyList[i];
                double v = localPheromonesByIdx[idx];
                if (v > tauMax) localPheromonesByIdx[idx] = tauMax;
                else if (v < tauMin) localPheromonesByIdx[idx] = tauMin;
            }
        }
    }

    private void inicializarFeromonas() {
        for (Edge e : graph.edges) {
            e.pheromone = config.initialPheromone;
        }
    }

    private void prepararArrayFeromonas() {
        int max = -1;
        for (Edge e : graph.edges) if (e.idx > max) max = e.idx;
        int size = Math.max(0, max + 1);
        localPheromonesByIdx = new double[size];
        localDirtyFlag = new boolean[size];
        localDirtyList = new int[size];
        localDirtyCount = 0;
    }

    private void resetLocalPheromones() {
        for (int i = 0; i < localDirtyCount; i++) {
            int idx = localDirtyList[i];
            localPheromonesByIdx[idx] = 0.0;
            localDirtyFlag[idx] = false;
        }
        localDirtyCount = 0;
    }

    private double getPheromone(Edge edge) {
        if (routeEvaluator == null) return edge.pheromone;
        int idx = edge.idx;
        if (idx < 0 || idx >= localDirtyFlag.length || !localDirtyFlag[idx]) {
            return config.initialPheromone;
        }
        return localPheromonesByIdx[idx];
    }

    private void addPheromone(Edge edge, double delta) {
        if (routeEvaluator == null) {
            edge.pheromone += delta;
            return;
        }
        int idx = edge.idx;
        if (idx < 0 || idx >= localDirtyFlag.length) {
            return;
        }
        if (!localDirtyFlag[idx]) {
            localPheromonesByIdx[idx] = config.initialPheromone;
            localDirtyFlag[idx] = true;
            localDirtyList[localDirtyCount++] = idx;
        }
        localPheromonesByIdx[idx] += delta;
    }

    private void actualizarMejorGlobal(Ant origen) {
        Ant dst = mejorGlobalReused;
        if (origen == dst) {
            mejorGlobal = dst; // ya estamos apuntando al mismo objeto
            return;
        }
        dst.reset();
        for (Node n : origen.path) dst.addNode(n);
        dst.edgesPath.addAll(origen.edgesPath);
        dst.totalCost = origen.totalCost;
        mejorGlobal = dst;
    }

    private boolean cumpleRestricciones(Ant ant) {
        if (routeEvaluator == null) {
            return CostFunction.cumpleRestriccionesDuras(ant, ant.edgesPath, envioContext);
        }
        return ant != null
                && ant.totalCost < Double.MAX_VALUE
                && ant.path != null
                && !ant.path.isEmpty()
                && envioContext.destinoICAO.equals(ant.path.get(ant.path.size() - 1).code);
    }

    private boolean isGoodEnough(Ant ant) {
        return ant != null
                && ant.edgesPath != null
                && !ant.edgesPath.isEmpty()
                && ant.edgesPath.size() <= 2
                && ant.totalCost <= GOOD_ENOUGH_COST;
    }

    private double greedyValue(Edge edge, AcoRouteEvaluator.Transition transition, GreedyStrategy strategy, Ant ant) {
        if (routeEvaluator == null || transition == null) {
            return CostFunction.heuristica(edge, envioContext, ant);
        }
        switch (strategy) {
            case EARLIEST_ARRIVAL:
                return 1.0 / (1.0 + transition.arrivalMin);
            case LOWEST_COST:
                return 1.0 / (1.0 + transition.cost);
            case BEST_DESIRABILITY:
            default:
                return transition.desirability;
        }
    }

    private boolean timeExpired() {
        return System.nanoTime() >= deadlineNano;
    }

    private enum GreedyStrategy {
        BEST_DESIRABILITY,
        EARLIEST_ARRIVAL,
        LOWEST_COST
    }

    // Reciclable: edge y transition se reasignan cada vez que addCandidate lo usa.
    private static final class Candidate {
        Edge edge;
        AcoRouteEvaluator.Transition transition;
    }
}
