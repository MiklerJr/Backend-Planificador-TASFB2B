package com.tasfb2b.planificador.algorithm.aco;

import java.util.List;

/**
 * Evaluates ACO moves against the real planning model used by the block engine.
 * The legacy ACO can still run without an evaluator; when present, ants only
 * traverse edges that are materializable for the current batch.
 */
public interface AcoRouteEvaluator {

    long initialReadyMin();

    Transition evaluate(Edge edge, long currentArrivalMin, int legIndex);

    boolean isCompleteRouteFeasible(List<Edge> route, List<Transition> transitions);

    default boolean isCompleteRouteOnTime(List<Edge> route, List<Transition> transitions) {
        return isCompleteRouteFeasible(route, transitions);
    }

    double routeCost(List<Edge> route, List<Transition> transitions);

    final class Transition {
        public final long departureMin;
        public final long arrivalMin;
        public final double desirability;
        public final double cost;

        public Transition(long departureMin, long arrivalMin, double desirability, double cost) {
            this.departureMin = departureMin;
            this.arrivalMin = arrivalMin;
            this.desirability = desirability;
            this.cost = cost;
        }
    }
}
