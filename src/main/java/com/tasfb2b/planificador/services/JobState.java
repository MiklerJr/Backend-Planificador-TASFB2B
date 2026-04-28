package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.SimulacionResponse;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Estado de una ejecución asíncrona del planificador.
 * Permite consultar progreso desde endpoints REST mientras la simulación corre
 * en un hilo dedicado (escenarios 2 y 3 pueden tomar 30-90 min con sleep activo).
 */
@Data
public class JobState {
    /** Identificador único del job (UUID). */
    private final String jobId;
    /** "2" para período, "3" para colapso. */
    private final String escenario;
    /** Factor K de aceleración. */
    private final int k;

    /** "ejecutando" | "completado" | "error". */
    public volatile String estado = "ejecutando";

    /** Bloque actualmente procesado (1-based, 0 antes de iniciar). */
    public volatile int bloqueActual = 0;
    /** Total de bloques previstos (se conoce tras construir el plan). */
    public volatile int totalBloques = 0;
    /** Promedio de Ta hasta el bloque actual (ms). */
    public volatile long taPromedioMs = 0L;

    /** Resultado completo cuando estado = "completado". Null mientras ejecuta. */
    public volatile SimulacionResponse resultado;
    /** Mensaje de error cuando estado = "error". */
    public volatile String error;
    /**
     * CSV de auditoría por envío (23 columnas). Generado al terminar el job y
     * descargable vía {@code GET /api/planificador/jobs/{jobId}/auditoria.csv}.
     */
    public volatile String auditoriaCsv;
    /** Tamaño en filas de la auditoría (sin contar la cabecera). */
    public volatile int auditoriaFilas;

    public final LocalDateTime inicio = LocalDateTime.now();
    public volatile LocalDateTime fin;

    /** Motor utilizado: "alns" o "aco". */
    public volatile String algoritmo = "alns";

    /**
     * Seed efectivo de la corrida. Si el cliente no pasa uno, se genera uno aleatorio
     * y se reporta aquí — cualquier valor reportado garantiza reproducibilidad si se
     * vuelve a invocar con el mismo seed, motor, k y cancelProb.
     */
    public volatile long seed = 0L;

    /**
     * Fecha de inicio efectiva del escenario 2 (puede ser distinta de la primera
     * ventana del dataset si el cliente la fijó en el endpoint). Null = primera ventana.
     */
    public volatile LocalDateTime fechaInicio;

    /**
     * CSV con muestra de hasta 25 envíos del escenario 2 con motor ALNS.
     * Solo se llena cuando escenario="2" y algoritmo="alns"; null en otros casos.
     */
    public volatile String muestraCsv;
    /** Número de filas en {@link #muestraCsv} (sin contar la cabecera). */
    public volatile int muestraFilas;

    public JobState(String jobId, String escenario, int k) {
        this.jobId     = jobId;
        this.escenario = escenario;
        this.k         = k;
    }

    public double getProgreso() {
        return totalBloques == 0 ? 0.0 : (double) bloqueActual / totalBloques;
    }
}
