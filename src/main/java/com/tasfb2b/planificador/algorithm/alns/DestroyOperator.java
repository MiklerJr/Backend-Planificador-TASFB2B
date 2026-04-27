package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.alns.AlnsSolution;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import java.util.List;

public interface DestroyOperator {
    /**
     * Remueve un porcentaje de maletas de sus rutas asignadas.
     * @param solution La solución actual a destruir parcialmente.
     * @param factor Porcentaje a destruir (ej. 0.20 para 20%).
     * @return Lista de lotes de maletas que se quedaron sin ruta.
     */
    List<LuggageBatch> destroy(AlnsSolution solution, double factor);
}