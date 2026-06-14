package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Read model agregado para el panel operativo (Tanda 1B), expuesto por
 * {@code GET /jobs/{id}/dashboard}. No modifica el job: toma las métricas del resultado final si
 * existe, o las deriva de los bloques publicados si sigue corriendo.
 *
 * <p>Byte-compatible con el mapa anterior: {@code fechaInicio}, {@code fin} y {@code error} se
 * omiten cuando son {@code null} ({@link JsonInclude.Include#NON_NULL}); {@code ultimoBloque} se
 * emite siempre (incluso {@code null} si aún no hay bloques publicados).
 */
@Data
public class DashboardResponse {
    private String jobId;
    private String escenario;
    private String algoritmo;
    private String estado;
    private int k;
    private long seed;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String fechaInicio;
    private String inicio;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String fin;
    private double progreso;
    private double progresoWarmup;
    private int bloqueActual;
    private int totalBloques;
    private int bloquesPublicados;
    private int posicionEnCola;
    private boolean canceladoPorUsuario;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
    private Metricas metricas;
    private Tasas tasas;
    /** Resumen del último bloque publicado; {@code null} (y se emite como tal) si no hay bloques. */
    private UltimoBloque ultimoBloque;

    /** Porcentajes derivados de {@link Metricas} (0..100). */
    @Data
    public static class Tasas {
        private double enrutamientoPct;
        private double sinRutaPct;
        private double cumpleSlaPct;
        private double tardadasPct;
    }

    /** Resumen del último bloque publicado para el panel (deltas en envíos, acumulados físicos). */
    @Data
    public static class UltimoBloque {
        private int bloqueIdx;
        private String horaInicio;
        private String horaFin;
        private int maletasProcesadas;
        private int maletasEnrutadas;
        private long maletasProcesadasAcum;
        private long maletasEnrutadasAcum;
        private long maletasEntregadasAcum;
        private long taMs;
        private int scMinutos;
    }
}
