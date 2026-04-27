package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AlgorithmACO {

    private Graph graph;
    private ConfigACO config;
    private CostFunction.EnvioContext envioContext;

    private List<Ant> ants = new ArrayList<>();
    private Ant mejorGlobal = null;
    private double mejorCostoGlobal = Double.MAX_VALUE;

    public AlgorithmACO(Graph graph, ConfigACO config, CostFunction.EnvioContext envioContext) {
        this.graph = graph;
        this.config = config;
        this.envioContext = envioContext;

        for (int i = 0; i < config.antCount; i++) {
            ants.add(new Ant());
        }
    }

    public void run(String start, String end) {

        Node startNode = graph.nodes.get(start);
        Node endNode = graph.nodes.get(end);
        inicializarFeromonas();

        int sinMejora = 0;

        for (int it = 0; it < config.iterations; it++) {

            for (Ant ant : ants) {
                ant.reset();
                buildSolution(ant, startNode, endNode);

                if (CostFunction.cumpleRestriccionesDuras(ant, ant.edgesPath, envioContext)
                        && ant.totalCost < mejorCostoGlobal) {
                    mejorCostoGlobal = ant.totalCost;
                    mejorGlobal = copiarAnt(ant);
                    sinMejora = 0;
                }
            }

            updatePheromones();

            if (config.maxNoImprovement > 0) {
                sinMejora++;
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
        ant.path.add(current);
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
                if (ant.edgesPath.isEmpty()) {
                    break;
                }
                ant.edgesPath.add(mejor);
                ant.path.add(mejor.to);
                current = mejor.to;
                current.releaseLoad(envioContext.cantidadMaletas);
                break;
            }

            current = mejor.to;
            ant.path.add(current);
            ant.edgesPath.add(mejor);
            lastEdge = mejor;
        }

        if (current.equals(end) && !ant.edgesPath.isEmpty()) {
            int idx = 0;
            for (Ant a : ants) {
                if (a.path.isEmpty()) {
                    a.path = ant.path;
                    a.edgesPath = ant.edgesPath;
                    a.totalCost = CostFunction.calcularCostoRuta(a, graph.edges, a.edgesPath, envioContext);
                    break;
                }
            }
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
        ant.path.add(current);
        Edge lastEdge = null;
        int maxEscalas = 10;
        int escalas = 0;
        boolean llego = false;

        while (!current.equals(end)) {

            List<Edge> options = graph.getEdgesFrom(current.code);

            if (options.isEmpty()) break;

            Edge chosen = selectEdge(ant, options);

            if (chosen == null) break;

            if (escalas >= maxEscalas) break;

            if (ant.visited(chosen.to)) break;

            if (!chosen.hasCapacity(envioContext.cantidadMaletas)) break;

            if (!chosen.to.hasStorageCapacity(envioContext.cantidadMaletas)) break;

            if (lastEdge != null && !CostFunction.tieneTiempoMinimoEscala(lastEdge,chosen)) break;

            current.releaseLoad(envioContext.cantidadMaletas);
            chosen.to.storeLoad(envioContext.cantidadMaletas);

            ant.totalCost += chosen.cost;

            if (chosen.to.equals(end)) {
                if (escalas == 0) {
                    break;
                }
                ant.edgesPath.add(chosen);
                ant.path.add(chosen.to);
                current = chosen.to;
                llego = true;
                break;
            }

            current = chosen.to;
            ant.path.add(current);
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
    private Edge selectEdge(Ant ant, List<Edge> edges) {

        double sum = 0.0;
        Map<Edge, Double> probs = new HashMap<>();

        for (Edge e : edges) {

            double pheromone = Math.pow(e.pheromone, config.alpha);
            double heuristic = Math.pow(CostFunction.heuristica(e, envioContext), config.beta);

            double value = pheromone * heuristic;

            probs.put(e, value);
            sum += value;
        }

        double rand = Math.random() * sum;
        double acc = 0;

        for (Map.Entry<Edge, Double> entry : probs.entrySet()) {

            acc += entry.getValue();

            if (acc >= rand) {
                return entry.getKey();
            }
        }

        return null;
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
