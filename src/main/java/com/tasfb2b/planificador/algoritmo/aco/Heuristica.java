package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

import java.util.List;

final class Heuristica {

    double heuristica(LoteEnvio batch, RutaCandidata route, int alternativasATiempo) {
        double slaMin = Math.max(1.0, batch.getHorasLimiteSla() * 60.0);
        double slackRatio = Math.max(0.0, Math.min(1.0, route.getHolguraMin() / slaMin));
        double slaScore = route.isCumpleSLA()
                ? 4.0
                : 0.05 / (1.0 + Math.max(0L, -route.getHolguraMin()) / 60.0);
        double scarcityAlt = 1.0 + 1.0 / Math.max(1, alternativasATiempo);
        double congestion = 1.0 / (1.0 + route.getCostoEscasez() * (0.5 + slackRatio));
        double capacityScore = 1.0 / (1.0 + Math.max(0.0, route.getPresion()) * 8.0);
        double routeShape = 1.0 / (1.0 + Math.max(0, route.getTramos() - 1) * 0.35);
        double urgency = 1.0 / Math.max(1.0, batch.getHorasLimiteSla());
        return slaScore * scarcityAlt * congestion * capacityScore * routeShape + urgency;
    }

    double heuristicaLote(LoteEnvio batch) {
        double urgency = 1.0 / Math.max(1.0, batch.getHorasLimiteSla());
        double volume = 1.0 + Math.log1p(Math.max(1, batch.getCantidad())) / 8.0;
        return urgency * volume;
    }

    double regret(LoteEnvio batch, List<RutaCandidata> rutas, int alternativasATiempo) {
        if (rutas.isEmpty()) return 0.0;
        double best = deseabilidadRuta(batch, rutas.get(0), alternativasATiempo);
        double second = rutas.size() > 1
                ? deseabilidadRuta(batch, rutas.get(1), alternativasATiempo)
                : 0.0;
        double scarcity = alternativasATiempo <= 1 ? 1.0 : 1.0 / alternativasATiempo;
        return scarcity + Math.max(0.0, (best - second) / Math.max(1.0, best));
    }

    private double deseabilidadRuta(LoteEnvio batch, RutaCandidata route, int alternativasATiempo) {
        return heuristica(batch, route, alternativasATiempo);
    }

    double costoSeleccion(LoteEnvio batch, RutaCandidata r) {
        double slaMin = Math.max(1.0, batch.getHorasLimiteSla() * 60.0);
        double slackRatio = Math.max(0.0, Math.min(1.0, r.getHolguraMin() / slaMin));
        return r.getCostoEscasez() * slackRatio + r.getTransitoMin() * 1e-4;
    }

    static int compararRutaBase(RutaCandidata a, RutaCandidata b) {
        int c = Boolean.compare(b.isCumpleSLA(), a.isCumpleSLA());
        if (c != 0) return c;
        c = Long.compare(Math.max(0L, -a.getHolguraMin()), Math.max(0L, -b.getHolguraMin()));
        if (c != 0) return c;
        c = Double.compare(a.getCostoEscasez(), b.getCostoEscasez());
        if (c != 0) return c;
        c = Double.compare(a.getPresion(), b.getPresion());
        if (c != 0) return c;
        c = Long.compare(a.getLlegadaMin(), b.getLlegadaMin());
        if (c != 0) return c;
        c = Integer.compare(a.getTramos(), b.getTramos());
        if (c != 0) return c;
        return Long.compare(b.getHolguraMin(), a.getHolguraMin());
    }
}
