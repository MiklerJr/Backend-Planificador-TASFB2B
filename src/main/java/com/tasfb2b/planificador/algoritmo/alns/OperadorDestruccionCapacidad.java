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
    public void setRandom(Random rng) {
        if (rng != null) this.rng = rng;
    }

    @Override
    public List<LoteEnvio> destroy(SolucionAlns solution, double factor) {
        List<LoteEnvio> all     = solution.getBatches();
        List<LoteEnvio> removed = new ArrayList<>();
        Set<LoteEnvio>  removedSet = new HashSet<>();
        int target = Math.max(1, (int)(all.size() * factor));

        // Prioridad 1: tardadas — siempre destruir para intentar mejorar
        for (LoteEnvio b : all) {
            if (!b.isCumpleSLA() && hasRoute(b)) {
                removed.add(b);
                removedSet.add(b);
            }
        }

        // Prioridad 2: completar con selección aleatoria de lotes enrutados
        if (removed.size() < target) {
            List<LoteEnvio> candidatos = new ArrayList<>();
            for (LoteEnvio b : all) {
                if (hasRoute(b) && !removedSet.contains(b)) candidatos.add(b);
            }
            Collections.shuffle(candidatos, rng);
            for (LoteEnvio b : candidatos) {
                if (removed.size() >= target) break;
                removed.add(b);
            }
        }

        return removed;
    }

    private boolean hasRoute(LoteEnvio b) {
        return b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
    }
}
