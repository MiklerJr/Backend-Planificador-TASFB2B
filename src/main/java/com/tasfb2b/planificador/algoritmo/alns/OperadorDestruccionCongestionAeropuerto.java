package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;


public class OperadorDestruccionCongestionAeropuerto implements OperadorDestruccion {

    private static final int TOP_AIRPORTS = 5;

    private Random rng = new Random();

    public OperadorDestruccionCongestionAeropuerto(Grafo graph) { }

    @Override
    public void setRandom(Random rng) {
        if (rng != null) this.rng = rng;
    }

    @Override
    public List<LoteEnvio> destroy(SolucionAlns solution, double factor) {
        List<LoteEnvio> all    = solution.getBatches();
        int                target = Math.max(1, (int)(all.size() * factor));

        // 1. Contar uso por aeropuerto (todas las escalas + destinos finales).
        Map<String, Integer> uso = new HashMap<>();
        for (LoteEnvio b : all) {
            if (b.getAssignedRoute() == null || b.getAssignedRoute().isEmpty()) continue;
            for (Arista e : b.getAssignedRoute()) {
                if (e.to != null && e.to.code != null) {
                    uso.merge(e.to.code, b.getQuantity(), Integer::sum);
                }
            }
        }

        if (uso.isEmpty()) return List.of();

        // 2. Top-N aeropuertos más cargados.
        Set<String> congestionados = uso.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed())
                .limit(TOP_AIRPORTS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // 3. Batches cuyas rutas pasan por al menos uno de los congestionados.
        List<LoteEnvio> candidatos = new ArrayList<>();
        for (LoteEnvio b : all) {
            if (b.getAssignedRoute() == null || b.getAssignedRoute().isEmpty()) continue;
            boolean pasa = false;
            for (Arista e : b.getAssignedRoute()) {
                if (e.to != null && congestionados.contains(e.to.code)) {
                    pasa = true;
                    break;
                }
            }
            if (pasa) candidatos.add(b);
        }

        if (candidatos.isEmpty()) return List.of();

        // 4. Aleatorizar dentro de los candidatos para no destruir siempre los mismos.
        Collections.shuffle(candidatos, rng);
        return candidatos.subList(0, Math.min(target, candidatos.size()));
    }
}
