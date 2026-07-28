package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.RutaCandidata;

import java.util.List;
import java.util.Set;

final class OpcionEnvio {
    final RefEnvio ref;
    final List<RutaCandidata> rutas;
    final int alternativasATiempo;
    final double regret;
    final double heuristica;
    final double peso;
    final String claveLote;
    final Set<Long> clavesOcupadas;

    OpcionEnvio(RefEnvio ref,
                List<RutaCandidata> rutas,
                int alternativasATiempo,
                double regret,
                double heuristica,
                double peso,
                String claveLote,
                Set<Long> clavesOcupadas) {
        this.ref = ref;
        this.rutas = rutas;
        this.alternativasATiempo = alternativasATiempo;
        this.regret = regret;
        this.heuristica = heuristica;
        this.peso = peso;
        this.claveLote = claveLote;
        this.clavesOcupadas = clavesOcupadas;
    }
}
