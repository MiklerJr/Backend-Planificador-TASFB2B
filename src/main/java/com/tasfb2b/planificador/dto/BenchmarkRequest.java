package com.tasfb2b.planificador.dto;

import lombok.Data;

import java.util.List;

/**
 * Request para iniciar un benchmark de calibración.
 * Todos los campos son opcionales: si se omiten, se usan los valores de
 * {@code planificador.benchmark} en application.yaml.
 */
@Data
public class BenchmarkRequest {
    /** Valores de K a probar (ej: [7, 14, 28, 50, 75]). */
    private List<Integer> kGrid;
    /** Probabilidades de cancelación a probar (ej: [0.0, 0.05, 0.10]). */
    private List<Double> cancelProbGrid;
    /** Repeticiones por combinación (promediar Ta). */
    private Integer repeticiones;
    /** Si true, también incluye corridas de escenario 3 (hasta colapso). */
    private boolean ejecutarColapso;
    /** Umbral usado en escenario 3 (default 0.20). */
    private Double umbralColapso;
}
