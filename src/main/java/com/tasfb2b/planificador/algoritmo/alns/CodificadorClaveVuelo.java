package com.tasfb2b.planificador.algoritmo.alns;

public final class CodificadorClaveVuelo {

    public static final int  BITS_DIA = 20;
    public static final long MIN_DIA  = 24 * 60L;
    public static final long MASCARA_DIA = (1L << BITS_DIA) - 1;

    public static final int  BITS_SLOT = 22;
    public static final long MASCARA_SLOT = (1L << BITS_SLOT) - 1;

    public static int indiceNodoDeSlot(long claveAlmacen) {
        return (int) (claveAlmacen >> BITS_SLOT);
    }

    public static long slotDe(long claveAlmacen) {
        return claveAlmacen & MASCARA_SLOT;
    }

    private CodificadorClaveVuelo() {}

    public static long claveVuelo(int indiceArista, long epochMin) {
        return ((long) indiceArista << BITS_DIA) | ((epochMin / MIN_DIA) & MASCARA_DIA);
    }

    public static long claveAeropuerto(int indiceNodo, long epochMin) {
        return ((long) indiceNodo << BITS_DIA) | ((epochMin / MIN_DIA) & MASCARA_DIA);
    }
}
