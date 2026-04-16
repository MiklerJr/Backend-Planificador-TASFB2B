package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.List;

public class AlgorithmACO {

    private Graph graph;
    private List<Ant> ants;

    private double alpha = 1;
    private double beta = 2;
    private double evaporation = 0.5;

    public AlgorithmACO(Graph graph, int antCount) {
        this.graph = graph;
        this.ants = new ArrayList<>();

        for (int i = 0; i < antCount; i++) {
            ants.add(new Ant());
        }
    }

    public void run(int iterations) {

    }
}