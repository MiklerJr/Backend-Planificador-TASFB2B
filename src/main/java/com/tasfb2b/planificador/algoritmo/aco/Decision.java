package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

final class Decision {
    final LoteEnvio batch;
    final RutaCandidata route;
    final String key;
    final String batchKey;
    final double heuristic;

    Decision(LoteEnvio batch, RutaCandidata route, String key, String batchKey, double heuristic) {
        this.batch = batch;
        this.route = route;
        this.key = key;
        this.batchKey = batchKey;
        this.heuristic = heuristic;
    }
}
