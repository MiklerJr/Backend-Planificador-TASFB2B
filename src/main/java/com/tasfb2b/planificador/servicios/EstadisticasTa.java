package com.tasfb2b.planificador.servicios;

/**
 * Acumulador de estadísticas del presupuesto Ta por bloque (min/max/suma/promedio) para
 * las métricas finales de la corrida y la advertencia de calibración (Ta cercano a Sa).
 */
final class EstadisticasTa {
    private long min = Long.MAX_VALUE;
    private long max = 0L;
    private long suma = 0L;
    private int n = 0;

    void acumular(long taMs) {
        if (taMs < min) min = taMs;
        if (taMs > max) max = taMs;
        suma += taMs;
        n++;
    }

    long min() {
        return n == 0 ? 0 : min;
    }

    long max() {
        return max;
    }

    long suma() {
        return suma;
    }

    long promedio() {
        return n == 0 ? 0 : suma / n;
    }
}
