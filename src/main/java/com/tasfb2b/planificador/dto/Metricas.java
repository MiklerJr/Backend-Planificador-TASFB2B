package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    // ── Detalle del colapso real (E1/E2/E3) — null si no hubo colapso ──────────────────
    /** Causa del colapso: "almacen_lleno" (almacén a capacidad) o "backlog_definitivo" (SLA vencido). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String motivoColapso;
    /** Detalle legible de dónde/qué colapsó (envío y/o almacén). Antes solo se logueaba. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String detalleColapso;
    /** Instante UTC del colapso (fin de la ventana del bloque donde ocurrió), ISO-8601. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String instanteColapsoUtc;

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
