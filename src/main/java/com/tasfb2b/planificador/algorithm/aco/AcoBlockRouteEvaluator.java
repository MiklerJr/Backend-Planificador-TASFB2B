package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class AcoBlockRouteEvaluator implements AcoRouteEvaluator {

    private static final long CONNECTION_MIN = 10L;
    private static final long DEST_STORAGE_MIN = 10L;
    private static final long DAY_MIN = 1440L;
    private static final long MAX_HORIZON_MIN = 3 * DAY_MIN;
    private static final double SLA_LATE_FIXED_COST = 10_000.0;

    private final GreedyRepairOperator enrutador;
    private final LuggageBatch batch;
    private final Map<Long, Integer> blockFlight;
    private final Map<Long, Integer> blockAirport;
    private final long readyMin;
    private final long slaMaxMin;
    // Cache de (edge, currentArrivalMin, legIndex) → Transition. Clave codificada
    // en un long para evitar alocaciones de MoveKey y reducir presión en el GC.
    // Layout: edge.idx (24 bits) | currentArrivalMin (36 bits) | legIndex (4 bits).
    private final Map<Long, Transition> transitionCache = new HashMap<>();
    private static final Transition INVALID = new Transition(-1L, -1L, 0.0, Double.MAX_VALUE);

    public AcoBlockRouteEvaluator(GreedyRepairOperator enrutador,
                                  LuggageBatch batch,
                                  Map<Long, Integer> blockFlight,
                                  Map<Long, Integer> blockAirport) {
        this.enrutador = enrutador;
        this.batch = batch;
        this.blockFlight = blockFlight;
        this.blockAirport = blockAirport;
        this.readyMin = GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime());
        this.slaMaxMin = (long) batch.getSlaLimitHours() * 60L;
    }

    @Override
    public long initialReadyMin() {
        return readyMin;
    }

    @Override
    public Transition evaluate(Edge edge, long currentArrivalMin, int legIndex) {
        long key = encodeKey(edge.idx, currentArrivalMin, legIndex);
        Transition cached = transitionCache.get(key);
        if (cached != null) {
            return cached == INVALID ? null : cached;
        }

        // Pre-filter por capacidad física: si el vuelo NUNCA puede llevar la
        // cantidad pedida por este batch, descartar antes de calcular tiempos.
        // Más barato que pasar por capacidadRestante() (lookup en ConcurrentHashMap).
        if (edge.capacity > 0 && edge.capacity < batch.getQuantity()) return cacheInvalid(key);

        long earliest = legIndex == 0 ? currentArrivalMin : currentArrivalMin + CONNECTION_MIN;
        long departure = enrutador.calcularProximaSalida(edge.depMinuteOfDay, earliest);
        long arrival = departure + edge.durationMinutes;
        long transit = (arrival + DEST_STORAGE_MIN) - readyMin;

        if (arrival - readyMin > MAX_HORIZON_MIN) return cacheInvalid(key);
        if (enrutador.capacidadRestante(edge, departure, blockFlight) < batch.getQuantity()) return cacheInvalid(key);
        if (edge.to != null && edge.to.capacity > 0) {
            if (enrutador.capacidadAlmacen(edge.to, arrival, blockAirport) < batch.getQuantity()) return cacheInvalid(key);
            if (!batch.getDestCode().equals(edge.to.code)
                    && enrutador.capacidadAlmacen(edge.to, arrival + DAY_MIN, blockAirport) < batch.getQuantity()) {
                return cacheInvalid(key);
            }
        }

        long wait = Math.max(0L, departure - currentArrivalMin);
        long lateness = Math.max(0L, transit - slaMaxMin);
        long slack = Math.max(0L, slaMaxMin - transit);
        double occupancyFactor = occupancyFactor(edge, departure);
        double scoreBase = 1.0 + wait + edge.durationMinutes
                + Math.max(0, 90 - Math.min(90, slack))
                + lateness * 25.0;
        double desirability = occupancyFactor / scoreBase;
        double cost = wait * CostFunction.W_WAIT_TIME
                + edge.durationMinutes * CostFunction.W_FLIGHT_TIME
                + latePenalty(lateness);
        Transition transition = new Transition(departure, arrival, desirability, cost);
        transitionCache.put(key, transition);
        return transition;
    }

    @Override
    public boolean isCompleteRouteFeasible(List<Edge> route, List<Transition> transitions) {
        if (route == null || route.isEmpty() || transitions == null || transitions.size() != route.size()) {
            return false;
        }
        Edge last = route.get(route.size() - 1);
        if (last.to == null || !batch.getDestCode().equals(last.to.code)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isCompleteRouteOnTime(List<Edge> route, List<Transition> transitions) {
        if (!isCompleteRouteFeasible(route, transitions)) return false;
        long arrival = transitions.get(transitions.size() - 1).arrivalMin;
        return (arrival + DEST_STORAGE_MIN) - readyMin <= slaMaxMin;
    }

    @Override
    public double routeCost(List<Edge> route, List<Transition> transitions) {
        if (!isCompleteRouteFeasible(route, transitions)) return Double.MAX_VALUE;

        double cost = 0.0;
        for (Transition t : transitions) {
            cost += t.cost;
        }
        long transit = transitions.get(transitions.size() - 1).arrivalMin + DEST_STORAGE_MIN - readyMin;
        long late = Math.max(0L, transit - slaMaxMin);
        long slack = Math.max(0L, slaMaxMin - transit);
        return cost + Math.max(0L, 120L - Math.min(120L, slack)) + latePenalty(late);
    }

    private double occupancyFactor(Edge edge, long departure) {
        int remaining = enrutador.capacidadRestante(edge, departure, blockFlight);
        if (edge.capacity <= 0) return 0.0001;
        double projected = (double) (edge.capacity - remaining + batch.getQuantity()) / edge.capacity;
        if (projected > 1.0) return 0.0001;
        if (projected > CostFunction.UMBRAL_AMBAR) return 0.30;
        if (projected > CostFunction.UMBRAL_VERDE) return 0.70;
        return 1.0;
    }

    private Transition cacheInvalid(long key) {
        transitionCache.put(key, INVALID);
        return null;
    }

    private double latePenalty(long latenessMinutes) {
        if (latenessMinutes <= 0L) return 0.0;
        return SLA_LATE_FIXED_COST + latenessMinutes * CostFunction.W_SLA_VIOLATION;
    }

    // 24 bits edge.idx | 36 bits currentArrivalMin (suficiente para ~130 años en min) | 4 bits legIndex
    private static long encodeKey(int edgeIdx, long currentArrivalMin, int legIndex) {
        long e = ((long) edgeIdx) & 0xFFFFFFL;
        long t = (currentArrivalMin & 0xFFFFFFFFFL) << 24;
        long l = ((long) (legIndex & 0xF)) << 60;
        return e | t | l;
    }
}
