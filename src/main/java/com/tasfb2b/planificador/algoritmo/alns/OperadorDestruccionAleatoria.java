package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Grafo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class OperadorDestruccionAleatoria implements OperadorDestruccion {

    private Random rng = new Random();

    public OperadorDestruccionAleatoria(Grafo graph) { }

    @Override
    public void setAleatorio(Random rng) {
        if (rng != null) this.rng = rng;
    }

    @Override
    public List<LoteEnvio> destruir(SolucionAlns solution, double factor) {
        List<LoteEnvio> all    = solution.getLotes();
        int                target = Math.max(1, (int)(all.size() * factor));

        List<LoteEnvio> candidatos = new ArrayList<>();
        for (LoteEnvio b : all) {
            if (b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty()) {
                candidatos.add(b);
            }
        }
        Collections.shuffle(candidatos, rng);
        return candidatos.subList(0, Math.min(target, candidatos.size()));
    }
}
