package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.grafo.Graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class RandomDestroyOperator implements DestroyOperator {

    private Random rng = new Random();

    public RandomDestroyOperator(Graph graph) { }

    @Override
    public void setRandom(Random rng) {
        if (rng != null) this.rng = rng;
    }

    @Override
    public List<LuggageBatch> destroy(AlnsSolution solution, double factor) {
        List<LuggageBatch> all    = solution.getBatches();
        int                target = Math.max(1, (int)(all.size() * factor));

        List<LuggageBatch> candidatos = new ArrayList<>();
        for (LuggageBatch b : all) {
            if (b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty()) {
                candidatos.add(b);
            }
        }
        Collections.shuffle(candidatos, rng);
        return candidatos.subList(0, Math.min(target, candidatos.size()));
    }
}
