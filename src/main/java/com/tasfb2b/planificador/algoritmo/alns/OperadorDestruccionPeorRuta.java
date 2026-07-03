package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Grafo;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class OperadorDestruccionPeorRuta implements OperadorDestruccion {

    public OperadorDestruccionPeorRuta(Grafo graph) { }

    @Override
    public List<LoteEnvio> destroy(SolucionAlns solution, double factor) {
        List<LoteEnvio> all    = solution.getBatches();
        int                target = Math.max(1, (int)(all.size() * factor));

        return all.stream()
                .filter(b -> b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty())
                .sorted(Comparator.comparingDouble(LoteEnvio::getTotalTransitTimeMins).reversed())
                .limit(target)
                .collect(Collectors.toList());
    }
}
