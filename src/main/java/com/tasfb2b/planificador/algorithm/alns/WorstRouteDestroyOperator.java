package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Graph;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Operador de destrucción que selecciona los lotes con mayor tiempo de tránsito.
 * Complementa a CapacityDestroyOperator: mientras ese prioriza tardadas + aleatorio,
 * este apunta a las rutas más largas, buscando reemplazarlas por caminos más cortos.
 */
public class WorstRouteDestroyOperator implements DestroyOperator {

    public WorstRouteDestroyOperator(Graph graph) { }

    @Override
    public List<LuggageBatch> destroy(AlnsSolution solution, double factor) {
        List<LuggageBatch> all    = solution.getBatches();
        int                target = Math.max(1, (int)(all.size() * factor));

        List<LuggageBatch> removed = all.stream()
                .filter(b -> b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty())
                .sorted(Comparator.comparingDouble(LuggageBatch::getTotalTransitTimeMins).reversed())
                .limit(target)
                .collect(Collectors.toList());

        removed.forEach(LuggageBatch::clearRoute);
        return removed;
    }
}
