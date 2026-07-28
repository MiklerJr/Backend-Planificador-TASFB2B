package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;

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

    private static final int TOP_AEROPUERTOS = 5;

    private Random rng = new Random();

    @Override
    public void setAleatorio(Random rng) {
        if (rng != null) this.rng = rng;
    }

    @Override
    public List<LoteEnvio> destruir(SolucionAlns solution, double factor) {
        List<LoteEnvio> all    = solution.getLotes();
        int                target = Math.max(1, (int)(all.size() * factor));

        Map<String, Integer> uso = new HashMap<>();
        for (LoteEnvio b : all) {
            if (b.getRutaAsignada() == null || b.getRutaAsignada().isEmpty()) continue;
            for (Arista e : b.getRutaAsignada()) {
                if (e.destino != null && e.destino.codigo != null) {
                    uso.merge(e.destino.codigo, b.getCantidad(), Integer::sum);
                }
            }
        }

        if (uso.isEmpty()) return List.of();

        Set<String> congestionados = uso.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed())
                .limit(TOP_AEROPUERTOS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        List<LoteEnvio> candidatos = new ArrayList<>();
        for (LoteEnvio b : all) {
            if (b.getRutaAsignada() == null || b.getRutaAsignada().isEmpty()) continue;
            boolean pasa = false;
            for (Arista e : b.getRutaAsignada()) {
                if (e.destino != null && congestionados.contains(e.destino.codigo)) {
                    pasa = true;
                    break;
                }
            }
            if (pasa) candidatos.add(b);
        }

        if (candidatos.isEmpty()) return List.of();

        Collections.shuffle(candidatos, rng);
        return candidatos.subList(0, Math.min(target, candidatos.size()));
    }
}
