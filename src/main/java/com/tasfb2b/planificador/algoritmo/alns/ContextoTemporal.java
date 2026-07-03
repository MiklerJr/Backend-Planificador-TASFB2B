package com.tasfb2b.planificador.algoritmo.alns;

import java.time.LocalDateTime;

public class ContextoTemporal {

    // ── Eje de datos (inmutable durante el bloque) ──────────────────────────
    public final LocalDateTime scStart;
    public final LocalDateTime scEnd;
    public final int scMinutos;
    public final int saMinutos;
    public final int k;
    public final int bloqueIdx;

    // ── Eje real (mutable: se llena al ejecutar el bloque) ──────────────────
    public long wallStartMs;
    public long wallEndMs;
    public long taMs;
    public long taRealMs;
    public double tasaSinRutaPrevia = 0.0;

    public ContextoTemporal(LocalDateTime scStart, LocalDateTime scEnd,
                           int scMinutos, int saMinutos, int k, int bloqueIdx) {
        this.scStart   = scStart;
        this.scEnd     = scEnd;
        this.scMinutos = scMinutos;
        this.saMinutos = saMinutos;
        this.k         = k;
        this.bloqueIdx = bloqueIdx;
    }

    public void marcarInicio() {
        this.wallStartMs = System.currentTimeMillis();
    }

    public void marcarFin(long taFijoMs) {
        this.wallEndMs = System.currentTimeMillis();
        this.taRealMs  = wallEndMs - wallStartMs;
        this.taMs      = taFijoMs > 0 ? taFijoMs : this.taRealMs;
    }

    public void marcarFin() {
        marcarFin(0L);
    }
}
