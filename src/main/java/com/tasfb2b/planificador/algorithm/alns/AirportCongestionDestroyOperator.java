package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Operador de destrucción enfocado en aeropuertos saturados.
 *
 * <p>Identifica los top-N aeropuertos más cargados en la solución actual
 * (suma de cantidades de batches que pasan por ellos como escala o destino)
 * y selecciona batches cuyas rutas pasan por al menos uno de ellos.
 * El ALNS al reasignar puede encontrar rutas alternativas que aprovechen
 * aeropuertos menos saturados, retrasando el colapso.
 *
 * <p>Complementa a {@link CapacityDestroyOperator} (foco en SLA) y
 * {@link WorstRouteDestroyOperator} (foco en tiempo de tránsito) atacando
 * la dimensión de capacidad de almacenes — clave para el escenario 3.
 */
public class AirportCongestionDestroyOperator implements DestroyOperator {

    /** Cuántos aeropuertos congestionados considerar. */
    private static final int TOP_AIRPORTS = 5;

    public AirportCongestionDestroyOperator(Graph graph) { }

    @Override
    public List<LuggageBatch> destroy(AlnsSolution solution, double factor) {
        List<LuggageBatch> all    = solution.getBatches();
        int                target = Math.max(1, (int)(all.size() * factor));

        // 1. Contar uso por aeropuerto (todas las escalas + destinos finales).
        Map<String, Integer> uso = new HashMap<>();
        for (LuggageBatch b : all) {
            if (b.getAssignedRoute() == null || b.getAssignedRoute().isEmpty()) continue;
            for (Edge e : b.getAssignedRoute()) {
                if (e.to != null && e.to.code != null) {
                    uso.merge(e.to.code, b.getQuantity(), Integer::sum);
                }
            }
        }

        if (uso.isEmpty()) return List.of();

        // 2. Top-N aeropuertos más cargados.
        Set<String> congestionados = uso.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed())
                .limit(TOP_AIRPORTS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // 3. Batches cuyas rutas pasan por al menos uno de los congestionados.
        List<LuggageBatch> candidatos = new ArrayList<>();
        for (LuggageBatch b : all) {
            if (b.getAssignedRoute() == null || b.getAssignedRoute().isEmpty()) continue;
            boolean pasa = false;
            for (Edge e : b.getAssignedRoute()) {
                if (e.to != null && congestionados.contains(e.to.code)) {
                    pasa = true;
                    break;
                }
            }
            if (pasa) candidatos.add(b);
        }

        if (candidatos.isEmpty()) return List.of();

        // 4. Aleatorizar dentro de los candidatos para no destruir siempre los mismos.
        Collections.shuffle(candidatos);
        return candidatos.subList(0, Math.min(target, candidatos.size()));
    }
}
