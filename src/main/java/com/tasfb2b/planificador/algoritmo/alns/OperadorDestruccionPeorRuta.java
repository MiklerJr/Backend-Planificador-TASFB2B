package com.tasfb2b.planificador.algoritmo.alns;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class OperadorDestruccionPeorRuta implements OperadorDestruccion {

    @Override
    public List<LoteEnvio> destruir(SolucionAlns solution, double factor) {
        List<LoteEnvio> all    = solution.getLotes();
        int                target = Math.max(1, (int)(all.size() * factor));

        return all.stream()
                .filter(b -> b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty())
                .sorted(Comparator.comparingDouble(LoteEnvio::getTiempoTransitoTotalMin).reversed())
                .limit(target)
                .collect(Collectors.toList());
    }
}
