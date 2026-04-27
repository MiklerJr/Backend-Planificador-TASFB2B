package com.tasfb2b.planificador.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Petición para lanzar una comparativa pareada ALNS vs ACO.
 *
 * <p>Para cada {@code r ∈ [0, repeticiones)} se ejecutan dos corridas con el mismo
 * {@code seed = seedBase + r}, una con ALNS y otra con ACO. Como ambos motores
 * resuelven el mismo problema (mismo dataset, mismas cancelaciones, misma
 * secuencia de batches por bloque), las métricas obtenidas son <b>pares</b>
 * apropiados para el test de Wilcoxon de rangos con signo.
 */
@Data
@NoArgsConstructor
public class ComparativaRequest {
    /** Factor K de aceleración. Default 14 (escenario 2). */
    private Integer k = 14;
    /** Probabilidad de cancelación por vuelo-día [0.0–1.0]. */
    private Double cancelProb = 0.0;
    /** Número de pares (ALNS, ACO) a ejecutar. Recomendado n ≥ 30 para Wilcoxon. */
    private Integer repeticiones = 30;
    /** Seed del primer par; las repeticiones siguientes usan seedBase+1, +2, ... */
    private Long seedBase = 42L;
    /** Si true, además del escenario 2 ejecuta también escenario 3 (hasta colapso). */
    private boolean ejecutarColapso = false;
    /** Umbral de colapso (solo aplica si ejecutarColapso=true). */
    private Double umbralColapso = 0.20;
}
