package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator.RouteCandidate;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;

final class Asignacion {
    final LuggageBatch batch;
    final RouteCandidate route;
    final String key;
    final String batchKey;

    Asignacion(LuggageBatch batch, RouteCandidate route, String key, String batchKey) {
        this.batch = batch;
        this.route = route;
        this.key = key;
        this.batchKey = batchKey;
    }
}
