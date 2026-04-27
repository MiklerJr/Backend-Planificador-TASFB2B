package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Operador de destrucción puramente aleatorio. No toma en cuenta calidad ni
 * congestión — su rol es <b>diversificar</b> la búsqueda del ALNS, abriendo
 * vecindarios que los operadores informados ({@link CapacityDestroyOperator},
 * {@link WorstRouteDestroyOperator}) ignorarían.
 *
 * <p>Útil cuando los pesos adaptativos hacen que un operador "informado" tome
 * el control y la búsqueda quede atrapada en un mínimo local.
 */
public class RandomDestroyOperator implements DestroyOperator {

    public RandomDestroyOperator(Graph graph) { }

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
        Collections.shuffle(candidatos);
        return candidatos.subList(0, Math.min(target, candidatos.size()));
    }
}
