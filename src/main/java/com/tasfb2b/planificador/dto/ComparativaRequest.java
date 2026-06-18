package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
    /** Motor a ejecutar: "ambos" (default), "alns" o "aco". */
    private String motor = "ambos";
    /** Alias de motor para requests en espaÃ±ol: "ambos", "alns" o "aco". */
    private String algoritmo;
    /** Si true, además del escenario 2 ejecuta también escenario 3 (hasta colapso). */
    private boolean ejecutarColapso = false;
    /** Umbral de colapso (solo aplica si ejecutarColapso=true). */
    private Double umbralColapso = 0.20;
    /**
     * Fecha de inicio arbitraria del escenario 2 (formato ISO "2026-01-05T00:00").
     * Null = primera ventana del dataset. Aplica a ambos motores en cada par para
     * que ALNS y ACO comparen el mismo subperíodo.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private LocalDateTime fechaInicio;

    /** Tamaño de ventana del planificador (Sa) en minutos. Null = default del yaml. */
    private Integer sa;
    /** Presupuesto Ta por bloque en segundos. Null = default del yaml. */
    private Integer ta;
    /** Duración total en días: ventanas = (dias·24·60)/sa. Null = legacy max-ventanas. */
    private Integer dias;
}
