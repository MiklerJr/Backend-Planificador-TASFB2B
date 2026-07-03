package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

import java.util.List;

/**
 * La visibilidad η del ACO: mide la deseabilidad de una ruta para un envío, más el regret
 * (cuánto se pierde si no se le da su mejor ruta) y el costo de selección de la semilla greedy.
 */
final class Heuristica {

    double heuristica(LoteEnvio batch, RutaCandidata route, int alternativasOnTime) {
        double slaMin = Math.max(1.0, batch.getSlaLimitHours() * 60.0);
        double slackRatio = Math.max(0.0, Math.min(1.0, route.getSlackMin() / slaMin));
        // J1: NO premiar velocidad/holgura (eso desperdiciaba capacidad escasa). En su
        // lugar, premiar rutas de baja congestión, y MÁS cuanto más holgado el envío.
        double slaScore = route.isCumpleSLA()
                ? 4.0
                : 0.05 / (1.0 + Math.max(0L, -route.getSlackMin()) / 60.0);
        double scarcityAlt = 1.0 + 1.0 / Math.max(1, alternativasOnTime);
        double congestion = 1.0 / (1.0 + route.getScarcityCost() * (0.5 + slackRatio));
        double capacityScore = 1.0 / (1.0 + Math.max(0.0, route.getPressure()) * 8.0);
        double routeShape = 1.0 / (1.0 + Math.max(0, route.getLegs() - 1) * 0.35);
        double urgency = 1.0 / Math.max(1.0, batch.getSlaLimitHours());
        return slaScore * scarcityAlt * congestion * capacityScore * routeShape + urgency;
    }

    double heuristicaBatch(LoteEnvio batch) {
        double urgency = 1.0 / Math.max(1.0, batch.getSlaLimitHours());
        double volume = 1.0 + Math.log1p(Math.max(1, batch.getQuantity())) / 8.0;
        return urgency * volume;
    }

    double regret(LoteEnvio batch, List<RutaCandidata> rutas, int alternativasOnTime) {
        if (rutas.isEmpty()) return 0.0;
        double best = routeDesirability(batch, rutas.get(0), alternativasOnTime);
        double second = rutas.size() > 1
                ? routeDesirability(batch, rutas.get(1), alternativasOnTime)
                : 0.0;
        double scarcity = alternativasOnTime <= 1 ? 1.0 : 1.0 / alternativasOnTime;
        return scarcity + Math.max(0.0, (best - second) / Math.max(1.0, best));
    }

    private double routeDesirability(LoteEnvio batch, RutaCandidata route, int alternativasOnTime) {
        return heuristica(batch, route, alternativasOnTime);
    }

    double costoSeleccion(LoteEnvio batch, RutaCandidata r) {
        double slaMin = Math.max(1.0, batch.getSlaLimitHours() * 60.0);
        double slackRatio = Math.max(0.0, Math.min(1.0, r.getSlackMin() / slaMin));
        return r.getScarcityCost() * slackRatio + r.getTransitMin() * 1e-4;
    }

    static int compararRutaBase(RutaCandidata a, RutaCandidata b) {
        int c = Boolean.compare(b.isCumpleSLA(), a.isCumpleSLA());
        if (c != 0) return c;
        c = Long.compare(Math.max(0L, -a.getSlackMin()), Math.max(0L, -b.getSlackMin()));
        if (c != 0) return c;
        c = Double.compare(a.getScarcityCost(), b.getScarcityCost());
        if (c != 0) return c;
        c = Double.compare(a.getPressure(), b.getPressure());
        if (c != 0) return c;
        c = Long.compare(a.getArrivalMin(), b.getArrivalMin());
        if (c != 0) return c;
        c = Integer.compare(a.getLegs(), b.getLegs());
        if (c != 0) return c;
        return Long.compare(b.getSlackMin(), a.getSlackMin());
    }
}
