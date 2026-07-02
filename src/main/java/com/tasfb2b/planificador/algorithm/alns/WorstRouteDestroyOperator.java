package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.grafo.Graph;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class WorstRouteDestroyOperator implements DestroyOperator {

    public WorstRouteDestroyOperator(Graph graph) { }

    @Override
    public List<LuggageBatch> destroy(AlnsSolution solution, double factor) {
        List<LuggageBatch> all    = solution.getBatches();
        int                target = Math.max(1, (int)(all.size() * factor));

        return all.stream()
                .filter(b -> b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty())
                .sorted(Comparator.comparingDouble(LuggageBatch::getTotalTransitTimeMins).reversed())
                .limit(target)
                .collect(Collectors.toList());
    }
}
