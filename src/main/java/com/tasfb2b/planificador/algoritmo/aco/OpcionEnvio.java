package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;

import java.util.List;
import java.util.Set;

final class OpcionEnvio {
    final RefEnvio ref;
    final List<RutaCandidata> rutas;
    final int alternativasOnTime;
    final double regret;
    final double heuristic;
    final double weight;
    final String batchKey;
    final Set<Long> occupiedKeys;

    OpcionEnvio(RefEnvio ref,
                List<RutaCandidata> rutas,
                int alternativasOnTime,
                double regret,
                double heuristic,
                double weight,
                String batchKey,
                Set<Long> occupiedKeys) {
        this.ref = ref;
        this.rutas = rutas;
        this.alternativasOnTime = alternativasOnTime;
        this.regret = regret;
        this.heuristic = heuristic;
        this.weight = weight;
        this.batchKey = batchKey;
        this.occupiedKeys = occupiedKeys;
    }
}
