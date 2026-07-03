package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * El rastro de feromonas τ del ACO: estado por bloque. Encapsula el mapa de feromonas y las
 * operaciones canónicas de la metaheurística: evaporación, depósito y peso (τ^α · η^β).
 */
final class RastroFeromonas {

    private static final double PHEROMONE_MIN = 0.10;
    private static final double PHEROMONE_MAX = 20.0;

    private final Map<String, Double> pheromones = new HashMap<>();
    private final ConfiguracionACO cfg;

    RastroFeromonas(ConfiguracionACO cfg) {
        this.cfg = cfg;
    }

    void evaporar(double evaporation) {
        if (pheromones.isEmpty()) return;
        Iterator<Map.Entry<String, Double>> it = pheromones.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Double> entry = it.next();
            double next = entry.getValue() * (1.0 - evaporation);
            if (next < PHEROMONE_MIN) {
                entry.setValue(PHEROMONE_MIN);
            } else {
                entry.setValue(next);
            }
        }
    }

    void depositar(SolucionBloque solucion, double q) {
        double delta = q / (1.0 + Math.max(0.0, solucion.cost));
        for (Asignacion a : solucion.asignaciones) {
            pheromones.merge(a.key, delta, (oldValue, add) -> Math.min(PHEROMONE_MAX, oldValue + add));
            pheromones.merge(a.batchKey, delta, (oldValue, add) -> Math.min(PHEROMONE_MAX, oldValue + add));
        }
    }

    double pesoBatch(String batchKey, double heuristic) {
        double pheromone = pheromones.getOrDefault(batchKey, cfg.initialPheromone);
        double pher = cfg.alpha == 1.0 ? pheromone : Math.pow(pheromone, cfg.alpha);
        return pher * Math.pow(Math.max(heuristic, 0.000001), cfg.beta);
    }

    double peso(Decision d) {
        double pheromone = pheromones.getOrDefault(d.key, cfg.initialPheromone);
        double pher = cfg.alpha == 1.0 ? pheromone : Math.pow(pheromone, cfg.alpha);
        return pher * Math.pow(Math.max(d.heuristic, 0.000001), cfg.beta);
    }

    static String claveBatch(LoteEnvio batch) {
        return "B|"
                + batch.getOriginCode() + '|'
                + batch.getDestCode() + '|'
                + batch.getReadyTime() + '|'
                + batch.getQuantity() + '|'
                + batch.getSlaLimitHours();
    }

    static String claveFeromona(String batchKey, RutaCandidata route) {
        return batchKey + '#' + route.signature();
    }
}
