package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

final class Asignacion {
    final LoteEnvio batch;
    final RutaCandidata route;
    final String key;
    final String batchKey;

    Asignacion(LoteEnvio batch, RutaCandidata route, String key, String batchKey) {
        this.batch = batch;
        this.route = route;
        this.key = key;
        this.batchKey = batchKey;
    }
}
