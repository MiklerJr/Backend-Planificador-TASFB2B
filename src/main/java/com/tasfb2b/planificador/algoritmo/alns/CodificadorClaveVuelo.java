package com.tasfb2b.planificador.algoritmo.alns;

public final class CodificadorClaveVuelo {

    public static final int  BITS_DIA = 20;
    public static final long MIN_DIA  = 24 * 60L;
    public static final long MASCARA_DIA = (1L << BITS_DIA) - 1;

    /**
     * Bits del slot en la clave de ALMACÉN (independiente de {@link #BITS_DIA}, que es de vuelo-día).
     * El slot es {@code epochMin / SLOT_ALMACEN_MIN} contado desde 1970, así que su magnitud depende de
     * la granularidad: con slots de 60 min cabía en 20 bits (~491k en 2026), pero con 15 min llega a
     * ~2,16M en 2031 y desbordaría la máscara de 20 bits — el truncamiento rompería la purga de memoria
     * (que reconstruye el día desde el slot) y las fechas de la telemetría. 22 bits cubren hasta 4,19M.
     */
    public static final int  BITS_SLOT = 22;
    public static final long MASCARA_SLOT = (1L << BITS_SLOT) - 1;

    /** Índice de nodo de una clave de almacén (bits altos). */
    public static int indiceNodoDeSlot(long claveAlmacen) {
        return (int) (claveAlmacen >> BITS_SLOT);
    }

    /** Slot de una clave de almacén (bits bajos). */
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
