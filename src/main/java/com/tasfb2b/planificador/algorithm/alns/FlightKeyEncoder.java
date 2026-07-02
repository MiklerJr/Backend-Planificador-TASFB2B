package com.tasfb2b.planificador.algorithm.alns;

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
