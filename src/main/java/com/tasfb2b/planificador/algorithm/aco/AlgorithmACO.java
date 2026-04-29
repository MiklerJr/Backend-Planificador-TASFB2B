package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AlgorithmACO {

    private Graph graph;
    private ConfigACO config;
    private CostFunction.EnvioContext envioContext;

    private List<Ant> ants = new ArrayList<>();
    private Ant mejorGlobal = null;
    private double mejorCostoGlobal = Double.MAX_VALUE;
    /** Fuente de aleatoriedad — reproducible si se setea con un seed explícito. */
    private Random rng = new Random();

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

    public void run(String start, String end) {

        Node startNode = graph.nodes.get(start);
        Node endNode = graph.nodes.get(end);
        inicializarFeromonas();
        precomputarHeuristicas();

        int sinMejora = 0;

        for (int it = 0; it < config.iterations; it++) {

            boolean huboMejora = false;
            for (Ant ant : ants) {
                ant.reset();
                buildSolution(ant, startNode, endNode);

                if (CostFunction.cumpleRestriccionesDuras(ant, ant.edgesPath, envioContext)
                        && ant.totalCost < mejorCostoGlobal) {
                    mejorCostoGlobal = ant.totalCost;
                    mejorGlobal = copiarAnt(ant);
                    huboMejora = true;
                }
            }

            updatePheromones();

            if (config.maxNoImprovement > 0) {
                sinMejora = huboMejora ? 0 : sinMejora + 1;
                if (sinMejora >= config.maxNoImprovement) {
                    break;
                }
            }
        }

        Ant mejor = getMejorAnt();
        if (mejor != null && !mejor.path.isEmpty()) {
            return;
        }

        buscarRutaGreedy(startNode, endNode);

        Ant mejorPostGreedy = getMejorAnt();
        if (mejorPostGreedy != null
                && CostFunction.cumpleRestriccionesDuras(mejorPostGreedy, mejorPostGreedy.edgesPath, envioContext)
                && mejorPostGreedy.totalCost < mejorCostoGlobal) {
            mejorCostoGlobal = mejorPostGreedy.totalCost;
            mejorGlobal = copiarAnt(mejorPostGreedy);
        }
    }

    private void buscarRutaGreedy(Node start, Node end) {
        start.storeLoad(envioContext.cantidadMaletas);

        Node current = start;
        Ant ant = new Ant();
        ant.addNode(current);
        Edge lastEdge = null;

        while (!current.equals(end)) {
            List<Edge> options = graph.getEdgesFrom(current.code);

            Edge mejor = null;
            double mejorValor = -1;

            for (Edge e : options) {
                if (ant.visited(e.to)) continue;
                if (!e.hasCapacity(envioContext.cantidadMaletas)) continue;
                if (!e.to.hasStorageCapacity(envioContext.cantidadMaletas)) continue;
                if (lastEdge != null && !CostFunction.tieneTiempoMinimoEscala(lastEdge, e)) continue;

                double valor = CostFunction.heuristica(e, envioContext);
                if (valor > mejorValor) {
                    mejorValor = valor;
                    mejor = e;
                }
            }

            if (mejor == null) break;

            current.releaseLoad(envioContext.cantidadMaletas);
            mejor.to.storeLoad(envioContext.cantidadMaletas);

            if (mejor.to.equals(end)) {
                ant.edgesPath.add(mejor);
                ant.addNode(mejor.to);
                current = mejor.to;
                current.releaseLoad(envioContext.cantidadMaletas);
                break;
            }

            current = mejor.to;
            ant.addNode(current);
            ant.edgesPath.add(mejor);
            lastEdge = mejor;
        }

        if (current.equals(end) && !ant.edgesPath.isEmpty()) {
            ant.totalCost = CostFunction.calcularCostoRuta(ant, graph.edges, ant.edgesPath, envioContext);
            // Sobreescribir directamente la primera hormiga con la solución de rescate
            Ant a = ants.get(0);
            a.path = new ArrayList<>(ant.path);
            a.edgesPath = new ArrayList<>(ant.edgesPath);
            a.totalCost = ant.totalCost;
        }
    }

    public Ant getMejorAnt() {
        if (mejorGlobal != null) {
            return mejorGlobal;
        }

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


    // CONSTRUCCIÓN DE SOLUCIÓN
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

            // 1. FILTRAR OPCIONES VÁLIDAS PRIMERO
            List<Edge> validOptions = new ArrayList<>();
            for (Edge e : options) {
                if (ant.visited(e.to)) continue;
                if (!e.hasCapacity(envioContext.cantidadMaletas)) continue;
                if (!e.to.hasStorageCapacity(envioContext.cantidadMaletas)) continue;
                if (lastEdge != null && !CostFunction.tieneTiempoMinimoEscala(lastEdge, e)) continue;
                validOptions.add(e);
            }

            // 2. SI NO HAY SALIDA, LA HORMIGA QUEDA ATRAPADA Y FALLA
            if (validOptions.isEmpty() || escalas >= maxEscalas) break;

            // 3. LA HORMIGA ELIGE SOLO ENTRE LAS OPCIONES SEGURAS
            Edge chosen = selectEdge(ant, validOptions);
            if (chosen == null) break;

            current.releaseLoad(envioContext.cantidadMaletas);
            chosen.to.storeLoad(envioContext.cantidadMaletas);

            if (chosen.to.equals(end)) {
                ant.edgesPath.add(chosen);
                ant.addNode(chosen.to);
                current = chosen.to;
                llego = true;
                break;
            }

            current = chosen.to;
            ant.addNode(current);
            ant.edgesPath.add(chosen);
            lastEdge = chosen;
            escalas++;
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


    // SELECCIÓN PROBABILÍSTICA
    // Hot path: sin HashMap, sin Math.pow, una sola pasada con array temporal.
    // Heurística^β ya viene cacheada en edge.heuristicCache (precomputarHeuristicas).
    // Para α==1.0 (config por defecto y único uso actual) se evita pow sobre la feromona.
    private Edge selectEdge(Ant ant, List<Edge> edges) {

        int n = edges.size();
        if (n == 0) return null;

        double[] weights = new double[n];
        double sum = 0.0;
        boolean alphaUno = config.alpha == 1.0;

        for (int i = 0; i < n; i++) {
            Edge e = edges.get(i);
            double pher = alphaUno ? e.pheromone : Math.pow(e.pheromone, config.alpha);
            double w = pher * e.heuristicCache;
            weights[i] = w;
            sum += w;
        }

        if (sum <= 0) return null;

        double rand = rng.nextDouble() * sum;
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += weights[i];
            if (acc >= rand) return edges.get(i);
        }
        return edges.get(n - 1);
    }

    /**
     * Pre-eleva la heurística de cada arista a β para el batch actual.
     * Como envioContext.cantidadMaletas es constante durante run(), basta
     * computarla una sola vez y reusar en cada selección de hormiga/iteración.
     */
    private void precomputarHeuristicas() {
        boolean betaDos = config.beta == 2.0;
        for (Edge e : graph.edges) {
            double h = CostFunction.heuristica(e, envioContext);
            e.heuristicCache = betaDos ? (h * h) : Math.pow(h, config.beta);
        }
    }

    // FEROMONAS
    private void updatePheromones() {

        // evaporación
        for (Edge e : graph.edges) {
            e.pheromone *= (1 - config.evaporation);
        }

        // refuerzo
        for (Ant ant : ants) {
            if (!CostFunction.cumpleRestriccionesDuras(ant, ant.edgesPath, envioContext)) {
                continue;
            }

            double deltaTau = config.q / (ant.totalCost + 1.0);
            for (Edge edge : ant.edgesPath) {
                edge.pheromone += deltaTau;
            }
        }
    }

    private void inicializarFeromonas() {
        for (Edge e : graph.edges) {
            e.pheromone = config.initialPheromone;
        }
    }

    private Ant copiarAnt(Ant original) {
        Ant copia = new Ant();
        copia.path = new ArrayList<>(original.path);
        copia.edgesPath = new ArrayList<>(original.edgesPath);
        copia.totalCost = original.totalCost;
        return copia;
    }
}
