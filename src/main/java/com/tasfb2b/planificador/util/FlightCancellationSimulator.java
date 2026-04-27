package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.algorithm.alns.FlightKeyEncoder;
import com.tasfb2b.planificador.algorithm.aco.Edge;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Genera el conjunto de combinaciones (vuelo, día) canceladas para la simulación.
 *
 * Por cada vuelo y por cada día del horizonte de simulación, se sortea un número
 * aleatorio: si es menor que {@code prob}, ese vuelo-día se marca como cancelado.
 * GreedyRepairOperator trata los vuelos cancelados como capacidad 0, obligando
 * al Dijkstra a buscar rutas alternativas — exactamente como ocurre en operaciones
 * reales cuando una maleta ya programada pierde su vuelo por cancelación.
 */
public class FlightCancellationSimulator {

    private static final long DAY_MIN = FlightKeyEncoder.DAY_MIN;

    private FlightCancellationSimulator() {}

    /**
     * @param edges          vuelos del grafo (cada Edge tiene edge.idx único)
     * @param startEpochDay  primer día de la simulación (LocalDate.toEpochDay())
     * @param endEpochDay    último día de la simulación + horizonte Dijkstra (3 días)
     * @param prob           probabilidad de cancelación por vuelo-día [0.0, 1.0]
     * @return conjunto de flightKeys cancelados (misma codificación que GreedyRepairOperator)
     */
    public static Set<Long> generate(List<Edge> edges,
                                     long startEpochDay, long endEpochDay,
                                     double prob) {
        if (prob <= 0.0 || edges.isEmpty()) return Collections.emptySet();

        Set<Long> cancelled = new HashSet<>();
        Random rng = new Random();

        for (Edge e : edges) {
            for (long day = startEpochDay; day <= endEpochDay; day++) {
                if (rng.nextDouble() < prob) {
                    cancelled.add(flightKey(e.idx, day * DAY_MIN));
                }
            }
        }
        return cancelled;
    }

    private static long flightKey(int edgeIdx, long epochMin) {
        return FlightKeyEncoder.flightKey(edgeIdx, epochMin);
    }
}
