package com.tasfb2b.planificador.dto;

import lombok.Data;

/**
 * Una fila del resultado de benchmark: combinación (escenario, K, cancelProb, repetición)
 * y todas las métricas observadas tras ejecutar la simulación.
 */
@Data
public class BenchmarkRow {
    private String escenario;          // "2" o "3"
    private int    k;
    private int    saMinutos;
    private int    scMinutos;          // K * Sa
    private double cancelProb;
    private int    repeticion;

    private int    totalBloques;
    private int    envios;
    private int    enrutadas;
    private int    sinRuta;
    private int    cumpleSLA;
    private int    tardadas;
    private long   maletasIndividuales;

    private double porcentajeCumpleSla;  // cumpleSLA / max(1, enrutadas)
    private double porcentajeSinRuta;    // sinRuta / max(1, envios)

    private long   taMinMs;
    private long   taMaxMs;
    private long   taPromedioMs;
    private long   tiempoTotalAlgMs;    // Ta acumulado (sin sleep)
    private long   tiempoRealMs;        // tiempo de pared total (con sleep si aplica)
    private double tiempoRealMin;       // tiempoRealMs / 60_000

    private int    backlogPico;
    private int    backlogActual;
    private int    sinRutaDefinitivo;

    private boolean colapsoDetectado;
    private int     bloqueColapso;
    private boolean advertenciaCalibracion;
}
