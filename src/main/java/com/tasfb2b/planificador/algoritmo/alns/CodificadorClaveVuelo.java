package com.tasfb2b.planificador.algoritmo.alns;

public final class CodificadorClaveVuelo {

    public static final int  BITS_DIA = 20;
    public static final long MIN_DIA  = 24 * 60L;
    public static final long MASCARA_DIA = (1L << BITS_DIA) - 1;

    private CodificadorClaveVuelo() {}

    public static long claveVuelo(int indiceArista, long epochMin) {
        return ((long) indiceArista << BITS_DIA) | ((epochMin / MIN_DIA) & MASCARA_DIA);
    }

    public static long claveAeropuerto(int indiceNodo, long epochMin) {
        return ((long) indiceNodo << BITS_DIA) | ((epochMin / MIN_DIA) & MASCARA_DIA);
    }
}
