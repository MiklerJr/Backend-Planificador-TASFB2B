package com.tasfb2b.planificador.algoritmo.alns;

import java.time.LocalDateTime;

public class ContextoTemporal {

    // Eje de datos
    public final LocalDateTime scInicio;
    public final LocalDateTime scFin;
    public final int scMinutos;
    public final int saMinutos;
    public final int k;
    public final int bloqueIdx;

    // Eje real
    public long relojInicioMs;
    public long relojFinMs;
    public long taMs;
    public long taRealMs;
    public double tasaSinRutaPrevia = 0.0;

    public ContextoTemporal(LocalDateTime scInicio, LocalDateTime scFin,
                           int scMinutos, int saMinutos, int k, int bloqueIdx) {
        this.scInicio   = scInicio;
        this.scFin     = scFin;
        this.scMinutos = scMinutos;
        this.saMinutos = saMinutos;
        this.k         = k;
        this.bloqueIdx = bloqueIdx;
    }

    public void marcarInicio() {
        this.relojInicioMs = System.currentTimeMillis();
    }

    public void marcarFin(long taFijoMs) {
        this.relojFinMs = System.currentTimeMillis();
        this.taRealMs  = relojFinMs - relojInicioMs;
        this.taMs      = taFijoMs > 0 ? taFijoMs : this.taRealMs;
    }

    public void marcarFin() {
        marcarFin(0L);
    }
}
