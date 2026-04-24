package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CapacityDestroyOperator implements DestroyOperator {

    public CapacityDestroyOperator(Graph graph) { }

    /**
     * Selecciona lotes para destruir siguiendo esta prioridad:
     *  1. Lotes tardados (SLA incumplido) — siempre se destruyen para intentar mejorarlos.
     *  2. Selección aleatoria del resto hasta alcanzar (factor × total).
     *
     * Solo desasigna la ruta del lote; la liberación de capacidad la realiza AlgorithmALNS
     * antes de llamar al operador de reparación.
     */
    @Override
    public List<LuggageBatch> destroy(AlnsSolution solution, double factor) {
        List<LuggageBatch> all     = solution.getBatches();
        List<LuggageBatch> removed = new ArrayList<>();
        int target = Math.max(1, (int)(all.size() * factor));

        // Prioridad 1: tardadas — siempre destruir para intentar mejorar
        for (LuggageBatch b : all) {
            if (!b.isCumpleSLA() && hasRoute(b)) {
                b.clearRoute();
                removed.add(b);
            }
        }

        // Prioridad 2: completar con selección aleatoria de lotes enrutados
        if (removed.size() < target) {
            List<LuggageBatch> candidatos = new ArrayList<>();
            for (LuggageBatch b : all) {
                if (hasRoute(b) && !removed.contains(b)) candidatos.add(b);
            }
            Collections.shuffle(candidatos);
            for (LuggageBatch b : candidatos) {
                if (removed.size() >= target) break;
                b.clearRoute();
                removed.add(b);
            }
        }

        return removed;
    }

    private boolean hasRoute(LuggageBatch b) {
        return b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
    }
}
