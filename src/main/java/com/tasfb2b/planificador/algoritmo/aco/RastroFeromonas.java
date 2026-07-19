package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class RastroFeromonas {

    private static final double FEROMONA_MIN = 0.10;
    private static final double FEROMONA_MAX = 20.0;

    private final Map<String, Double> pheromones = new HashMap<>();
    private final ConfiguracionACO cfg;

    RastroFeromonas(ConfiguracionACO cfg) {
        this.cfg = cfg;
    }

    void evaporar(double evaporacion) {
        if (pheromones.isEmpty()) return;
        Iterator<Map.Entry<String, Double>> it = pheromones.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Double> entry = it.next();
            double next = entry.getValue() * (1.0 - evaporacion);
            if (next < FEROMONA_MIN) {
                entry.setValue(FEROMONA_MIN);
            } else {
                entry.setValue(next);
            }
        }
    }

    void depositar(SolucionBloque solucion, double q) {
        double delta = q / (1.0 + Math.max(0.0, solucion.costo));
        for (Asignacion a : solucion.asignaciones) {
            pheromones.merge(a.clave, delta, (oldValue, add) -> Math.min(FEROMONA_MAX, oldValue + add));
            pheromones.merge(a.claveLote, delta, (oldValue, add) -> Math.min(FEROMONA_MAX, oldValue + add));
        }
    }

    double pesoLote(String batchKey, double heuristic) {
        double pheromone = pheromones.getOrDefault(batchKey, cfg.feromonaInicial);
        double pher = cfg.alpha == 1.0 ? pheromone : Math.pow(pheromone, cfg.alpha);
        return pher * Math.pow(Math.max(heuristic, 0.000001), cfg.beta);
    }

    double peso(Decision d) {
        double pheromone = pheromones.getOrDefault(d.clave, cfg.feromonaInicial);
        double pher = cfg.alpha == 1.0 ? pheromone : Math.pow(pheromone, cfg.alpha);
        return pher * Math.pow(Math.max(d.heuristica, 0.000001), cfg.beta);
    }

    static String claveLote(LoteEnvio batch) {
        return "B|"
                + batch.getCodigoOrigen() + '|'
                + batch.getCodigoDestino() + '|'
                + batch.getTiempoListo() + '|'
                + batch.getCantidad() + '|'
                + batch.getHorasLimiteSla();
    }

    static String claveFeromona(String batchKey, RutaCandidata route) {
        return batchKey + '#' + route.signature();
    }
}
