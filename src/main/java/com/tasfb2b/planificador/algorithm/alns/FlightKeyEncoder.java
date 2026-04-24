package com.tasfb2b.planificador.algorithm.alns;

/**
 * Codificación compartida de claves long para vuelo-día y aeropuerto-día.
 *
 * Formato: bits [63..20] = índice de arista o nodo, bits [19..0] = día epoch.
 * DAY_BITS=20 soporta hasta día 1,048,575 (año ~4880), suficiente para el horizonte del sistema.
 */
public final class FlightKeyEncoder {

    public static final int  DAY_BITS = 20;
    public static final long DAY_MIN  = 24 * 60L;
    public static final long DAY_MASK = (1L << DAY_BITS) - 1;

    private FlightKeyEncoder() {}

    public static long flightKey(int edgeIdx, long epochMin) {
        return ((long) edgeIdx << DAY_BITS) | ((epochMin / DAY_MIN) & DAY_MASK);
    }

    public static long airportKey(int nodeIdx, long epochMin) {
        return ((long) nodeIdx << DAY_BITS) | ((epochMin / DAY_MIN) & DAY_MASK);
    }
}
