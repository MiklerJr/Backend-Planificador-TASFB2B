package com.tasfb2b.planificador.algorithm.alns;

import java.util.List;
import java.util.Random;

public interface DestroyOperator {
    /**
     * Remueve un porcentaje de maletas de sus rutas asignadas.
     * @param solution La solución actual a destruir parcialmente.
     * @param factor Porcentaje a destruir (ej. 0.20 para 20%).
     * @return Lista de lotes de maletas que se quedaron sin ruta.
     */
    List<LuggageBatch> destroy(AlnsSolution solution, double factor);

    /**
     * Inyecta una fuente de aleatoriedad (para reproducibilidad cuando se fija un seed).
     * Implementación por defecto: no-op (operadores deterministas pueden ignorarla).
     */
    default void setRandom(Random rng) {}
}