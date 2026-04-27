package com.tasfb2b.planificador.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Estado y resultado completo de un benchmark de calibración.
 * Construido incrementalmente por {@code BenchmarkService}: las filas se llenan
 * a medida que cada combinación de parámetros termina.
 */
@Data
@NoArgsConstructor
public class BenchmarkResult {
    private String        jobId;
    /** "ejecutando" | "completado" | "error". */
    private String        estado;
    private String        error;
    private LocalDateTime inicio;
    private LocalDateTime fin;

    private int  filasTotales;
    private int  filasCompletadas;
    /** Combinación en curso (ej: "K=14, cancelProb=0.05, rep=2/3"). */
    private String configActual;

    private List<BenchmarkRow> filas = new ArrayList<>();
    private Recomendacion recomendacion;

    /**
     * Recomendación final: K óptimo para escenarios 2 y 3, con justificación.
     * {@code escenario2K} o {@code escenario3K} pueden ser null si no se ejecutó
     * ese escenario o ningún K cumplió los criterios.
     */
    @Data
    @NoArgsConstructor
    public static class Recomendacion {
        private Integer escenario2K;
        private String  razon2;
        private Integer escenario3K;
        private String  razon3;
    }
}
