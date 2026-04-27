package com.tasfb2b.planificador.algorithm.alns;

import java.time.LocalDateTime;

/**
 * Encapsula los dos ejes temporales del modelo de planificación programada fija
 * (Tasf.B2B). El cliente exige que estos ejes estén separados y sean visibles:
 *
 * <ul>
 *   <li><b>Eje de datos</b> (lo que el algoritmo "ve"):
 *       {@code [scStart, scEnd)} cubre {@code Sc = K * Sa} minutos por bloque.</li>
 *   <li><b>Eje real</b> (tiempo de pared del operador):
 *       {@code wallStartMs..wallEndMs}, con {@code Ta} = duración medida del bloque.</li>
 * </ul>
 *
 * <p>El planificador real ejecuta cada {@code Sa} minutos y debe cumplir
 * {@code Ta < Sa} para no colapsar la operación; cada ejecución consume
 * {@code Sc} minutos de datos. K es el factor de aceleración (1 = tiempo real,
 * 14 ≈ 3 días, 75 ≈ colapso).
 */
public class TemporalContext {

    // ── Eje de datos (inmutable durante el bloque) ──────────────────────────
    /** Inicio del rango de datos consumido por este bloque (inclusive). */
    public final LocalDateTime scStart;
    /** Fin del rango de datos consumido por este bloque (exclusivo). */
    public final LocalDateTime scEnd;
    /** {@code Sc = K * Sa} en minutos: cantidad de datos consumida por ejecución. */
    public final int scMinutos;
    /** {@code Sa} en minutos: salto entre ejecuciones del algoritmo. */
    public final int saMinutos;
    /** {@code K}: factor de aceleración. */
    public final int k;
    /** Índice 0-based de este bloque dentro de la simulación. */
    public final int bloqueIdx;

    // ── Eje real (mutable: se llena al ejecutar el bloque) ──────────────────
    /** Tiempo de pared al iniciar el procesamiento del bloque (ms desde epoch). */
    public long wallStartMs;
    /** Tiempo de pared al terminar el procesamiento del bloque (ms desde epoch). */
    public long wallEndMs;
    /** {@code Ta} = duración real del procesamiento, en ms. */
    public long taMs;
    /**
     * Tasa de sinRuta del bloque inmediatamente anterior (0.0 para el primer bloque).
     * Permite a {@code procesarBloque} elevar las iteraciones del ALNS cuando
     * la simulación se acerca al colapso, dedicando más cómputo a recuperar batches.
     */
    public double tasaSinRutaPrevia = 0.0;

    public TemporalContext(LocalDateTime scStart, LocalDateTime scEnd,
                           int scMinutos, int saMinutos, int k, int bloqueIdx) {
        this.scStart   = scStart;
        this.scEnd     = scEnd;
        this.scMinutos = scMinutos;
        this.saMinutos = saMinutos;
        this.k         = k;
        this.bloqueIdx = bloqueIdx;
    }

    /** Marca el inicio del eje real (llamar justo antes del procesamiento). */
    public void marcarInicio() {
        this.wallStartMs = System.currentTimeMillis();
    }

    /** Marca el fin del eje real y calcula Ta (llamar justo después del procesamiento). */
    public void marcarFin() {
        this.wallEndMs = System.currentTimeMillis();
        this.taMs      = wallEndMs - wallStartMs;
    }
}
