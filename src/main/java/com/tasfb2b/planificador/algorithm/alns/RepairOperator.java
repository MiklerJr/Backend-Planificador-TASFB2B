package com.tasfb2b.planificador.algorithm.alns;

import java.util.List;
import java.util.Map;

public interface RepairOperator {
    /**
     * Asigna rutas a los lotes sin ruta, actualizando los mapas de ocupación del bloque.
     *
     * @param solution     solución parcial en la que se reinsertarán los lotes
     * @param unassigned   lotes que necesitan nueva ruta
     * @param blockFlight  ocupación acumulada de vuelos para este bloque (mutable)
     * @param blockAirport ocupación acumulada de aeropuertos para este bloque (mutable)
     */
    void repair(AlnsSolution solution, List<LuggageBatch> unassigned,
                Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport);
}
