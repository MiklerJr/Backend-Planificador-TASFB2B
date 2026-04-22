package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.alns.AlnsSolution;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import java.util.List;

public interface RepairOperator {
    /**
     * Intenta asignar nuevas rutas a los lotes de equipaje sueltos.
     * @param solution La solución parcial donde se reinsertarán.
     * @param unassigned Lista de lotes de maletas sin ruta asignada.
     */
    void repair(AlnsSolution solution, List<LuggageBatch> unassigned);
}