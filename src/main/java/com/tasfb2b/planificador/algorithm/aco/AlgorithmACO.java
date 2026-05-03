package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class AlgorithmACO {

    private final Graph graph;
    private final ConfigACO config;
    private final CostFunction.EnvioContext envioContext;
    private AcoRouteEvaluator routeEvaluator;
    private Map<Edge, Double> localPheromones;
    private static final int TOP_K_CANDIDATES = 8;
    private static final double GOOD_ENOUGH_COST = 360.0;

    private final List<Ant> ants = new ArrayList<>();
    private Ant mejorGlobal = null;
    private double mejorCostoGlobal = Double.MAX_VALUE;
    private Random rng = new Random();
    private long deadlineNano = Long.MAX_VALUE;

    public AlgorithmACO(Graph graph, ConfigACO config, CostFunction.EnvioContext envioContext) {
        this.graph = graph;
        this.config = config;
        this.envioContext = envioContext;

        for (int i = 0; i < config.antCount; i++) {
            ants.add(new Ant());
        }
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

    public void run(String start, String end) {
        Node startNode = graph.nodes.get(start);
        Node endNode = graph.nodes.get(end);
        if (startNode == null || endNode == null) return;
        if (timeExpired()) return;

        if (buscarMejorDirecto(startNode, endNode)) return;
        if (timeExpired()) return;

        if (routeEvaluator == null) {
            inicializarFeromonas();
            precomputarHeuristicas();
        } else {
            localPheromones = new HashMap<>();
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
                    mejorGlobal = copiarAnt(ant);
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
            mejorGlobal = copiarAnt(mejorPostGreedy);
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

        mejorGlobal = new Ant();
        mejorGlobal.addNode(startNode);
        mejorGlobal.addNode(endNode);
        mejorGlobal.edgesPath.add(mejorEdge);
        mejorGlobal.totalCost = mejorCosto;
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
        Ant ant = new Ant();
        ant.addNode(current);
        Edge lastEdge = null;
        long currentArrivalMin = routeEvaluator != null ? routeEvaluator.initialReadyMin() : 0L;
        List<AcoRouteEvaluator.Transition> transitions = new ArrayList<>();

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
            Ant a = ants.get(0);
            a.path = new ArrayList<>(ant.path);
            a.edgesPath = new ArrayList<>(ant.edgesPath);
            a.totalCost = ant.totalCost;
            if (cumpleRestricciones(a) && a.totalCost < mejorCostoGlobal) {
                mejorCostoGlobal = a.totalCost;
                mejorGlobal = copiarAnt(a);
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
        List<AcoRouteEvaluator.Transition> transitions = new ArrayList<>();
        int maxEscalas = 10;
        int escalas = 0;
        boolean llego = false;

        while (!current.equals(end)) {
            if (timeExpired()) break;
            List<Edge> options = graph.getEdgesFrom(current.code);
            if (options.isEmpty()) break;

            List<Candidate> validOptions = new ArrayList<>();
            for (Edge e : options) {
                if (ant.visited(e.to)) continue;

                if (routeEvaluator != null) {
                    AcoRouteEvaluator.Transition transition = routeEvaluator.evaluate(e, currentArrivalMin, ant.edgesPath.size());
                    if (transition == null) continue;
                    validOptions.add(new Candidate(e, transition));
                } else {
                    if (!e.hasCapacity(envioContext.cantidadMaletas)) continue;
                    if (!e.to.hasStorageCapacity(envioContext.cantidadMaletas)) continue;
                    if (lastEdge != null && !CostFunction.tieneTiempoMinimoEscala(lastEdge, e)) continue;
                    validOptions.add(new Candidate(e, null));
                }
            }

            if (validOptions.isEmpty() || escalas >= maxEscalas) break;
            if (useTopK && validOptions.size() > TOP_K_CANDIDATES) {
                validOptions.sort((a, b) -> Double.compare(weightOf(b), weightOf(a)));
                validOptions = new ArrayList<>(validOptions.subList(0, TOP_K_CANDIDATES));
            }

            Candidate chosen = selectEdge(validOptions);
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

            // JFLXX: La hormiga actualiza su reloj al aterrizar
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


    // SELECCIÓN PROBABILÍSTICA
    // Hot path: sin HashMap, sin Math.pow, una sola pasada con array temporal.
    // Heurística^β ya viene cacheada en edge.heuristicCache (precomputarHeuristicas).
    // Para α==1.0 (config por defecto y único uso actual) se evita pow sobre la feromona.
    // SELECCIÓN PROBABILÍSTICA DINÁMICA
    private Candidate selectEdge(List<Candidate> edges) {
        int n = edges.size();
        if (n == 0) return null;

        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += weightOf(edges.get(i));
        }

        if (sum <= 0.0) return null;

        double rand = rng.nextDouble() * sum;
        double acc = 0.0;
        for (int i = 0; i < n; i++) {
            acc += weightOf(edges.get(i));
            if (acc >= rand) return edges.get(i);
        }
        return edges.get(n - 1);
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
            double h = CostFunction.heuristica(e, envioContext, new Ant());
            e.heuristicCache = betaDos ? (h * h) : Math.pow(h, config.beta);
        }
    }

    private void updatePheromones() {
        if (routeEvaluator == null) {
            for (Edge e : graph.edges) {
                e.pheromone *= (1 - config.evaporation);
            }
        } else {
            localPheromones.replaceAll((edge, pheromone) -> pheromone * (1 - config.evaporation));
        }

        for (Ant ant : ants) {
            if (!cumpleRestricciones(ant)) continue;

            double deltaTau = config.q / (ant.totalCost + 1.0);
            for (Edge edge : ant.edgesPath) {
                addPheromone(edge, deltaTau);
            }
        }

        // 3. LÍMITES MAX-MIN: Evita la convergencia prematura
        // Ninguna ruta será 100% ignorada, ni 100% dominante
        double tauMax = 10.0;
        double tauMin = 0.5;

        if (routeEvaluator == null) {
            for (Edge e : graph.edges) {
                if (e.pheromone > tauMax) e.pheromone = tauMax;
                else if (e.pheromone < tauMin) e.pheromone = tauMin;
            }
        } else {
            localPheromones.replaceAll((e, p) -> Math.min(tauMax, Math.max(tauMin, p)));
        }
    }

    private void inicializarFeromonas() {
        for (Edge e : graph.edges) {
            e.pheromone = config.initialPheromone;
        }
    }

    private double getPheromone(Edge edge) {
        if (routeEvaluator == null) return edge.pheromone;
        return localPheromones.getOrDefault(edge, config.initialPheromone);
    }

    private void addPheromone(Edge edge, double delta) {
        if (routeEvaluator == null) {
            edge.pheromone += delta;
        } else {
            localPheromones.put(edge, getPheromone(edge) + delta);
        }
    }

    private Ant copiarAnt(Ant original) {
        Ant copia = new Ant();
        copia.path = new ArrayList<>(original.path);
        copia.edgesPath = new ArrayList<>(original.edgesPath);
        copia.totalCost = original.totalCost;
        return copia;
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

    private static final class Candidate {
        final Edge edge;
        final AcoRouteEvaluator.Transition transition;

        Candidate(Edge edge, AcoRouteEvaluator.Transition transition) {
            this.edge = edge;
            this.transition = transition;
        }
    }
}
