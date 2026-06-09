package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * ACO clásico de DIAGNÓSTICO. Construye una ruta origen→destino con hormigas que
 * eligen el siguiente vuelo por selección probabilística (feromona^α · heurística^β).
 *
 * <p>Usa los contadores globales mutables {@link Edge#usedCapacity} y
 * {@link Node#storageUsed} (vía {@code storeLoad}/{@code releaseLoad}) como modelo
 * de capacidad. Estos NO respetan el modelo temporal flight-day/airport-day de la
 * vía de producción, por lo que sus métricas no son comparables con las de
 * producción y NO deben usarse para resultados que vayan al cliente.
 *
 * <p>Solo se invoca desde flujos de simulación/diagnóstico
 * ({@code PlanificadorService.intentarPlanificarEnvio}) y tests. La vía de
 * producción (motor=aco que llega al front) es {@code AcoBlockEngine} +
 * {@code GreedyRepairOperator}, que no usa esta clase.
 */
public class AlgorithmACO {

    private final Graph graph;
    private final ConfigACO config;
    private CostFunction.EnvioContext envioContext;

    private final List<Ant> ants = new ArrayList<>();
    private final Ant mejorGlobalReused = new Ant();
    private final Ant heuristicaAnt = new Ant();
    private Ant mejorGlobal = null;
    private double mejorCostoGlobal = Double.MAX_VALUE;
    private Random rng = new Random();

    // Buffer reutilizable para candidatos en buildSolution/buscarRutaGreedy.
    // Evita ArrayList<Candidate> y new Candidate(...) en el inner loop.
    private Candidate[] candidates = new Candidate[32];
    private int candidatesCount;

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

    /**
     * Permite reutilizar una instancia de {@code AlgorithmACO} entre envíos sin
     * reasignar hormigas ni buffers. Llamar antes de {@link #run(String, String)}
     * con el contexto del nuevo envío.
     */
    public void setEnvioContext(CostFunction.EnvioContext envioContext) {
        this.envioContext = envioContext;
    }

    public void run(String start, String end) {
        Node startNode = graph.nodes.get(start);
        Node endNode = graph.nodes.get(end);
        if (startNode == null || endNode == null) return;

        // Reset entre corridas para que la instancia pueda ser reusada.
        mejorGlobal = null;
        mejorCostoGlobal = Double.MAX_VALUE;

        if (buscarMejorDirecto(startNode, endNode)) return;

        inicializarFeromonas();
        precomputarHeuristicas();

        int sinMejora = 0;

        for (int it = 0; it < config.iterations; it++) {
            boolean huboMejora = false;

            for (int antIdx = 0; antIdx < ants.size(); antIdx++) {
                Ant ant = ants.get(antIdx);
                ant.reset();
                buildSolution(ant, startNode, endNode);

                if (cumpleRestricciones(ant) && ant.totalCost < mejorCostoGlobal) {
                    mejorCostoGlobal = ant.totalCost;
                    actualizarMejorGlobal(ant);
                    huboMejora = true;
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

        buscarRutaGreedy(startNode, endNode);

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

        for (Edge e : graph.getEdgesFrom(startNode.code)) {
            if (!e.to.equals(endNode)) continue;
            if (!e.hasCapacity(envioContext.cantidadMaletas)) continue;
            if (!e.to.hasStorageCapacity(envioContext.cantidadMaletas)) continue;

            double costo = e.cost;
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
        return true;
    }

    private void buscarRutaGreedy(Node start, Node end) {
        start.storeLoad(envioContext.cantidadMaletas);

        Node current = start;
        Ant ant = ants.get(0);
        ant.reset();
        ant.addNode(current);
        Edge lastEdge = null;

        while (!current.equals(end)) {
            Edge mejor = null;
            double mejorValor = -1.0;

            for (Edge e : graph.getEdgesFrom(current.code)) {
                if (ant.visited(e.to)) continue;
                if (!e.hasCapacity(envioContext.cantidadMaletas)) continue;
                if (!e.to.hasStorageCapacity(envioContext.cantidadMaletas)) continue;
                if (lastEdge != null && !CostFunction.tieneTiempoMinimoEscala(lastEdge, e)) continue;

                double valor = greedyValue(e, ant);
                if (valor > mejorValor) {
                    mejorValor = valor;
                    mejor = e;
                }
            }

            if (mejor == null) break;

            current.releaseLoad(envioContext.cantidadMaletas);
            mejor.to.storeLoad(envioContext.cantidadMaletas);

            ant.edgesPath.add(mejor);
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

        if (current.equals(end) && !ant.edgesPath.isEmpty()) {
            ant.totalCost = CostFunction.calcularCostoRuta(ant, graph.edges, ant.edgesPath, envioContext);
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

    private void buildSolution(Ant ant, Node start, Node end) {
        start.storeLoad(envioContext.cantidadMaletas);

        Node current = start;
        ant.addNode(current);
        Edge lastEdge = null;
        int maxEscalas = 10;
        int escalas = 0;
        boolean llego = false;

        while (!current.equals(end)) {
            List<Edge> options = graph.getEdgesFrom(current.code);
            if (options.isEmpty()) break;

            candidatesCount = 0;
            int optSize = options.size();
            int demanda = envioContext.cantidadMaletas;
            for (int i = 0; i < optSize; i++) {
                Edge e = options.get(i);
                if (ant.visited(e.to)) continue;
                // Pre-filter por capacidad física del vuelo: descarta cuando la
                // demanda nominal ya excede la capacidad del avión.
                if (e.capacity > 0 && e.capacity < demanda) continue;
                if (!e.hasCapacity(demanda)) continue;
                if (!e.to.hasStorageCapacity(demanda)) continue;
                if (lastEdge != null && !CostFunction.tieneTiempoMinimoEscala(lastEdge, e)) continue;
                addCandidate(e);
            }

            if (candidatesCount == 0 || escalas >= maxEscalas) break;

            Candidate chosen = selectEdge(candidatesCount);
            if (chosen == null) break;

            current.releaseLoad(envioContext.cantidadMaletas);
            chosen.edge.to.storeLoad(envioContext.cantidadMaletas);

            ant.edgesPath.add(chosen.edge);
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

        ant.totalCost = CostFunction.calcularCostoRuta(ant, graph.edges, ant.edgesPath, envioContext);
    }

    private void addCandidate(Edge edge) {
        if (candidatesCount == candidates.length) {
            candidates = Arrays.copyOf(candidates, candidates.length * 2);
        }
        Candidate slot = candidates[candidatesCount];
        if (slot == null) {
            slot = new Candidate();
            candidates[candidatesCount] = slot;
        }
        slot.edge = edge;
        candidatesCount++;
    }

    // SELECCIÓN PROBABILÍSTICA
    // Hot path: sin HashMap, sin Math.pow, una sola pasada con array temporal.
    // Heurística^β ya viene cacheada en edge.heuristicCache (precomputarHeuristicas).
    // Para α==1.0 (config por defecto) se evita pow sobre la feromona.
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
        double heuristic = c.edge.heuristicCache;
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

        // 1. EVAPORACIÓN
        for (Edge e : graph.edges) {
            e.pheromone *= (1 - config.evaporation);
        }

        // 2. DEPÓSITO por las hormigas que cumplen restricciones
        for (Ant ant : ants) {
            if (!cumpleRestricciones(ant)) continue;

            double deltaTau = config.q / (ant.totalCost + 1.0);
            for (Edge edge : ant.edgesPath) {
                addPheromone(edge, deltaTau);
            }
        }

        // 3. LÍMITES MAX-MIN: Evita la convergencia prematura
        for (Edge e : graph.edges) {
            if (e.pheromone > tauMax) e.pheromone = tauMax;
            else if (e.pheromone < tauMin) e.pheromone = tauMin;
        }
    }

    private void inicializarFeromonas() {
        for (Edge e : graph.edges) {
            e.pheromone = config.initialPheromone;
        }
    }

    private double getPheromone(Edge edge) {
        return edge.pheromone;
    }

    private void addPheromone(Edge edge, double delta) {
        edge.pheromone += delta;
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
        return CostFunction.cumpleRestriccionesDuras(ant, ant.edgesPath, envioContext);
    }

    private double greedyValue(Edge edge, Ant ant) {
        return CostFunction.heuristica(edge, envioContext, ant);
    }

    // Reciclable: edge se reasigna cada vez que addCandidate lo usa.
    private static final class Candidate {
        Edge edge;
    }
}
