package com.tasfb2b.planificador.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fila pareada ALNS vs ACO de una comparativa.
 *
 * <p>Cada fila corresponde a un valor de seed; ALNS y ACO resolvieron el mismo
 * problema (mismas cancelaciones, mismas maletas). Las columnas {@code alns_*}
 * y {@code aco_*} son las observaciones pareadas que alimentan a Wilcoxon.
 *
 * <p>{@code escenario} = "2" para período completo, "3" para hasta‑colapso.
 */
@Data
@NoArgsConstructor
public class ComparativaRow {
    private String escenario;
    private int    rep;
    private long   seed;
    private int    k;

    // ── Métricas ALNS ─────────────────────────────────────────────────
    private int    alnsEnvios;
    private int    alnsEnrutadas;
    private int    alnsSinRuta;
    private int    alnsCumpleSLA;
    private int    alnsTardadas;
    private double alnsPctSLA;
    private double alnsPctSinRuta;
    private long   alnsTaPromedioMs;
    private long   alnsTaMaxMs;
    private long   alnsTiempoRealMs;
    private Double alnsMsPorPaquete; // <--- NUEVO CAMPO
    private int    alnsBacklogPico;
    private int    alnsSinRutaDefinitivo;
    private boolean alnsCollapsoDetectado;
    private int    alnsBloqueColapso;

    // ── Métricas ACO ──────────────────────────────────────────────────
    private int    acoEnvios;
    private int    acoEnrutadas;
    private int    acoSinRuta;
    private int    acoCumpleSLA;
    private int    acoTardadas;
    private double acoPctSLA;
    private double acoPctSinRuta;
    private long   acoTaPromedioMs;
    private long   acoTaMaxMs;
    private long   acoTiempoRealMs;
    private Double acoMsPorPaquete;  // <--- NUEVO CAMPO
    private int    acoBacklogPico;
    private int    acoSinRutaDefinitivo;
    private boolean acoCollapsoDetectado;
    private int    acoBloqueColapso;
}
