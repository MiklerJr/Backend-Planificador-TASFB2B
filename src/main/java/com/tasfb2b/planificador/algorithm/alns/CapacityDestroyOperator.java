package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.alns.AlnsSolution;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.algorithm.aco.Graph; // Se reutiliza el grafo
import java.util.ArrayList;
import java.util.List;

public class CapacityDestroyOperator implements DestroyOperator {

    private Graph graph;

    public CapacityDestroyOperator(Graph graph) {
        this.graph = graph;
    }

    @Override
    public List<LuggageBatch> destroy(AlnsSolution solution, double factor) {
        List<LuggageBatch> removed = new ArrayList<>();
        int amountToRemove = (int) (solution.getBatches().size() * factor);

        // Lógica de Destrucción:
        // 1. Identificar aeropuertos cuya ocupación > 90% (Límites 500-800)
        // 2. Remover las rutas de los lotes que pasan por ahí

        for (int i = 0; i < amountToRemove; i++) {
            LuggageBatch batch = solution.getBatches().get(i);
            batch.clearRoute(); // Se desasigna la ruta
            removed.add(batch);
        }
        return removed;
    }
}