package com.tasfb2b.planificador.dto.simulacion;

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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String motivoColapso;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String detalleColapso;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String instanteColapsoUtc;

    // ── Métricas de calibración (modelo Ta/Sa) ─────────────────────────
    private long taMinMs;
    private long taMaxMs;
    private long taPromedioMs;
    private long tiempoTotalAlgMs;
    private boolean advertenciaCalibracion;

    // ── Métricas del backlog acumulativo ───────────────────────────────
    private int backlogActual;
    private int backlogPico;
    private int sinRutaDefinitivo;
}
