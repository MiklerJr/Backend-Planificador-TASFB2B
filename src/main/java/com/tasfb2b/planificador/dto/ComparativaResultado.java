package com.tasfb2b.planificador.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Estado y resultado completo de una comparativa pareada ALNS vs ACO.
 *
 * <p>Construido incrementalmente por {@code ComparativaService}: las filas se
 * llenan a medida que cada par (ALNS, ACO) termina. El cliente puede pollear
 * {@code estado} para ver progreso o esperar a {@code estado="completado"} y
 * descargar el CSV con todas las observaciones pareadas.
 */
@Data
@NoArgsConstructor
public class ComparativaResultado {
    private String        jobId;
    /** "ejecutando" | "completado" | "error". */
    private String        estado;
    private String        error;
    private LocalDateTime inicio;
    private LocalDateTime fin;

    private int    filasTotales;
    private int    filasCompletadas;
    /** Configuración en curso (ej: "rep 7/30 — ALNS K=14 cp=0.05 seed=49"). */
    private String configActual;

    private List<ComparativaRow> filas = new ArrayList<>();
}
