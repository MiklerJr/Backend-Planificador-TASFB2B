package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator.RouteCandidate;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;

final class Decision {
    final LuggageBatch batch;
    final RouteCandidate route;
    final String key;
    final String batchKey;
    final double heuristic;

    Decision(LuggageBatch batch, RouteCandidate route, String key, String batchKey, double heuristic) {
        this.batch = batch;
        this.route = route;
        this.key = key;
        this.batchKey = batchKey;
        this.heuristic = heuristic;
    }
}
