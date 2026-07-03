package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Selección probabilística estilo ACO: ruleta ponderada por τ^α · η^β (feromona × heurística),
 * con una tasa de exploración que ocasionalmente elige al azar. Decide qué envío atender y qué
 * ruta darle en cada paso de una hormiga.
 */
final class RuletaSeleccion {

    private static final double EXPLORATION_RATE = 0.15;

    private final RastroFeromonas feromonas;
    private final Heuristica heuristica;
    private final Random random;

    RuletaSeleccion(RastroFeromonas feromonas, Heuristica heuristica, Random random) {
        this.feromonas = feromonas;
        this.heuristica = heuristica;
        this.random = random;
    }

    OpcionEnvio elegirLote(List<OpcionEnvio> opciones) {
        if (opciones.isEmpty()) return null;
        if (random.nextDouble() < EXPLORATION_RATE) {
            return opciones.get(random.nextInt(opciones.size()));
        }

        double total = 0.0;
        for (OpcionEnvio opcion : opciones) {
            total += opcion.peso;
        }
        if (total <= 0.0) return opciones.get(0);

        double pick = random.nextDouble() * total;
        double acc = 0.0;
        for (OpcionEnvio opcion : opciones) {
            acc += opcion.peso;
            if (acc >= pick) return opcion;
        }
        return opciones.get(opciones.size() - 1);
    }

    Decision elegirRuta(OpcionEnvio opcion) {
        List<Decision> decisiones = new ArrayList<>(opcion.rutas.size());
        for (RutaCandidata ruta : opcion.rutas) {
            decisiones.add(new Decision(opcion.ref.lote, ruta,
                    RastroFeromonas.claveFeromona(opcion.claveLote, ruta), opcion.claveLote,
                    heuristica.heuristica(opcion.ref.lote, ruta, opcion.alternativasATiempo)));
        }
        return elegirDecision(decisiones);
    }

    private Decision elegirDecision(List<Decision> decisiones) {
        if (decisiones.isEmpty()) return null;
        if (random.nextDouble() < EXPLORATION_RATE) {
            return decisiones.get(random.nextInt(decisiones.size()));
        }

        double total = 0.0;
        for (Decision d : decisiones) {
            total += feromonas.peso(d);
        }
        if (total <= 0.0) {
            Decision best = null;
            for (Decision d : decisiones) {
                if (best == null || d.heuristica > best.heuristica) best = d;
            }
            return best;
        }

        double pick = random.nextDouble() * total;
        double acc = 0.0;
        for (Decision d : decisiones) {
            acc += feromonas.peso(d);
            if (acc >= pick) return d;
        }
        return decisiones.get(decisiones.size() - 1);
    }
}
