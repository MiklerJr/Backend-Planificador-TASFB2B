package com.tasfb2b.planificador.dto;

import lombok.Data;

@Data
public class Metricas {
    private int  procesadas;           // número de envíos (LuggageBatch)
    private int  enrutadas;            // envíos con ruta asignada
    private int  sinRuta;              // envíos sin ruta
    private int  cumpleSLA;            // envíos enrutados dentro del plazo
    private int  tardadas;             // envíos enrutados fuera del plazo
    private long maletasIndividuales;  // suma de cantidades físicas (bag count real)
    private int  vuelosCancelados;     // número de combinaciones vuelo-día canceladas
    private long tiempoEjecucionMs;
    private boolean collapsoDetectado; // escenario 3: true si se detectó colapso
    private int     bloqueColapso;     // escenario 3: índice del bloque donde ocurrió (-1 si no)

    // ── Métricas de calibración (modelo Ta/Sa) ─────────────────────────
    /** Ta mínimo observado en algún bloque (ms). */
    private long taMinMs;
    /** Ta máximo observado en algún bloque (ms). */
    private long taMaxMs;
    /** Ta promedio sobre todos los bloques procesados (ms). */
    private long taPromedioMs;
    /** Tiempo total dedicado al algoritmo (suma de Ta de todos los bloques, ms). */
    private long tiempoTotalAlgMs;
    /** Si Ta excedió 0.9 * Sa en algún bloque → la simulación necesita recalibrar K. */
    private boolean advertenciaCalibracion;

    // ── Métricas del backlog acumulativo ───────────────────────────────
    /** Tamaño del backlog al final de la simulación (sinRuta + replanificables). */
    private int backlogActual;
    /** Pico histórico del backlog durante la simulación. */
    private int backlogPico;
    /** Batches descartados definitivamente (SLA vencido o tope excedido). */
    private int sinRutaDefinitivo;
}
