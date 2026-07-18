package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Grafo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class OperadorDestruccionCapacidad implements OperadorDestruccion {

    private Random rng = new Random();

    public OperadorDestruccionCapacidad(Grafo graph) { }

    @Override
    public void setAleatorio(Random rng) {
        if (rng != null) this.rng = rng;
    }

    @Override
    public List<LoteEnvio> destruir(SolucionAlns solution, double factor) {
        List<LoteEnvio> all     = solution.getLotes();
        List<LoteEnvio> removed = new ArrayList<>();
        Set<LoteEnvio>  removedSet = new HashSet<>();
        int target = Math.max(1, (int)(all.size() * factor));

        for (LoteEnvio b : all) {
            if (!b.isCumpleSLA() && tieneRuta(b)) {
                removed.add(b);
                removedSet.add(b);
            }
        }

        if (removed.size() < target) {
            List<LoteEnvio> candidatos = new ArrayList<>();
            for (LoteEnvio b : all) {
                if (tieneRuta(b) && !removedSet.contains(b)) candidatos.add(b);
            }
            Collections.shuffle(candidatos, rng);
            for (LoteEnvio b : candidatos) {
                if (removed.size() >= target) break;
                removed.add(b);
            }
        }

        return removed;
    }

    private boolean tieneRuta(LoteEnvio b) {
        return b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty();
    }
}
