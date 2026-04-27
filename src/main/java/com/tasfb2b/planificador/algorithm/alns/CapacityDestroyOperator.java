package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CapacityDestroyOperator implements DestroyOperator {

    public CapacityDestroyOperator(Graph graph) { }

    /**
     * Selecciona lotes para destruir siguiendo esta prioridad:
     *  1. Lotes tardados (SLA incumplido) — siempre se destruyen para intentar mejorarlos.
     *  2. Selección aleatoria del resto hasta alcanzar (factor × total).
     *
     * IMPORTANTE: NO limpia las rutas aquí. AlgorithmALNS llama a releaseFromBlock()
     * (que necesita la ruta intacta) y luego clearRoute() en el orden correcto.
     */
    @Override
    public List<LuggageBatch> destroy(AlnsSolution solution, double factor) {
        List<LuggageBatch> all     = solution.getBatches();
        List<LuggageBatch> removed = new ArrayList<>();
        Set<LuggageBatch>  removedSet = new HashSet<>();
        int target = Math.max(1, (int)(all.size() * factor));

        // Prioridad 1: tardadas — siempre destruir para intentar mejorar
        for (LuggageBatch b : all) {
            if (!b.isCumpleSLA() && hasRoute(b)) {
                removed.add(b);
                removedSet.add(b);
            }
        }

        // Prioridad 2: completar con selección aleatoria de lotes enrutados
        if (removed.size() < target) {
            List<LuggageBatch> candidatos = new ArrayList<>();
            for (LuggageBatch b : all) {
                if (hasRoute(b) && !removedSet.contains(b)) candidatos.add(b);
            }
            Collections.shuffle(candidatos);
            for (LuggageBatch b : candidatos) {
                if (removed.size() >= target) break;
                removed.add(b);
            }
        }

        return removed;
    }

    private boolean hasRoute(LuggageBatch b) {
        return b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
    }
}
