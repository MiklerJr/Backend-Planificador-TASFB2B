package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;

final class RefEnvio {
    final LuggageBatch batch;
    final int index;

    RefEnvio(LuggageBatch batch, int index) {
        this.batch = batch;
        this.index = index;
    }
}
