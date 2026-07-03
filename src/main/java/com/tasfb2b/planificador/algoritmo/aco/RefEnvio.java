package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

final class RefEnvio {
    final LoteEnvio lote;
    final int indice;

    RefEnvio(LoteEnvio lote, int indice) {
        this.lote = lote;
        this.indice = indice;
    }
}
