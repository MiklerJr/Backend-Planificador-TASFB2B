package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AlgorithmACO {

    private Graph graph;
    private ConfigACO config;

    private List<Ant> ants = new ArrayList<>();

    public AlgorithmACO(Graph graph, ConfigACO config) {
        this.graph = graph;
        this.config = config;

        for (int i = 0; i < config.antCount; i++) {
            ants.add(new Ant());
        }
    }

    public void run(String start, String end) {

        Node startNode = graph.nodes.get(start);
        Node endNode = graph.nodes.get(end);

        for (int it = 0; it < config.iterations; it++) {

            for (Ant ant : ants) {
                ant.reset();
                buildSolution(ant, startNode, endNode);
            }

            updatePheromones();
        }
    }


    // CONSTRUCCIÓN DE SOLUCIÓN
    private void buildSolution(Ant ant, Node start, Node end) {

        Node current = start;
        ant.path.add(current);

        while (!current.equals(end)) {

            List<Edge> options = graph.getEdgesFrom(current.code);

            Edge chosen = selectEdge(ant, options);

            if (chosen == null) break;

            if (!chosen.hasCapacity(1)) break;

            chosen.useCapacity(1);

            ant.totalCost += chosen.cost;

            current = chosen.to;
            ant.path.add(current);
        }
    }


    // SELECCIÓN PROBABILÍSTICA
    private Edge selectEdge(Ant ant, List<Edge> edges) {

        double sum = 0.0;
        Map<Edge, Double> probs = new HashMap<>();

        for (Edge e : edges) {

            double pheromone = Math.pow(e.pheromone, config.alpha);
            double heuristic = Math.pow(1.0 / (e.cost + 1), config.beta);

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

            for (int i = 0; i < ant.path.size() - 1; i++) {

                Node from = ant.path.get(i);
                Node to = ant.path.get(i + 1);

                for (Edge e : graph.edges) {

                    if (e.from.equals(from) && e.to.equals(to)) {
                        e.pheromone += 1.0 / (ant.totalCost + 1);
                    }
                }
            }
        }
    }
}