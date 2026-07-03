package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

final class RefEnvio {
    final LoteEnvio batch;
    final int index;

    RefEnvio(LoteEnvio batch, int index) {
        this.batch = batch;
        this.index = index;
    }
}
