package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Graph;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptive Large Neighbourhood Search (ALNS) con Simulated Annealing.
 *
 * La "A" (Adaptativa) se implementa mediante pesos de selección que se actualizan
 * cada {@code segmentLength} iteraciones según el rendimiento de cada operador:
 *
 *   w_i ← (1 − r) · w_i + r · (score_i / max(1, uses_i))
 *
 * Recompensas por iteración:
 *   σ1 = 3 — nueva solución global mejor
 *   σ2 = 2 — mejora sobre la solución actual
 *   σ3 = 1 — aceptada por SA (peor, pero pasa el criterio de Boltzmann)
 *   σ4 = 0 — rechazada
 *
 * Operadores de destroy disponibles (seleccionados por ruleta proporcional a pesos):
 *   0 → CapacityDestroyOperator  (tardadas primero + aleatorio)
 *   1 → WorstRouteDestroyOperator (rutas de mayor tiempo de tránsito)
 */
@Slf4j
public class AlgorithmALNS {

    // ── Hiperparámetros ──────────────────────────────────────────────────────
    public double destroyFactor = 0.20;   // fracción de lotes destruidos por iter
    public double initialTemp   = 500.0;  // temperatura SA inicial
    public double coolingRate   = 0.85;   // factor de enfriamiento por iteración
    public double minTemp       = 0.1;    // temperatura mínima (parada anticipada)
    public int    minBlockSize  = 3;      // bloques menores no ejecutan ALNS
    public int    segmentLength = 3;      // iters entre actualizaciones de pesos

    // ── Parámetros adaptativos (Ropke & Pisinger 2006) ───────────────────────
    private static final double REWARD_NEW_BEST    = 3.0;
    private static final double REWARD_IMPROVEMENT = 2.0;
    private static final double REWARD_ACCEPTED    = 1.0;
    private static final double REACTION_FACTOR    = 0.15; // r ∈ (0,1]

    // ── Estado del algoritmo ─────────────────────────────────────────────────
    private final Graph                  graph;
    private final GreedyRepairOperator   enrutador;
    private final List<DestroyOperator>  destroyOps;

    // Pesos adaptativos: un elemento por operador de destroy
    private final double[] weights;
    private final double[] scores;  // acumulado en el segmento actual
    private final int[]    uses;    // usos en el segmento actual

    private AlnsSolution     currentSolution;
    private AlnsSolution     bestSolution;
    private Map<Long,Integer> currentFlight;
    private Map<Long,Integer> currentAirport;
    private Map<Long,Integer> bestFlight;
    private Map<Long,Integer> bestAirport;

    private double temperature;

    // ────────────────────────────────────────────────────────────────────────

    /**
     * @param graph         grafo aeropuertos/vuelos
     * @param enrutador     repair operator compartido (contiene ocupación global)
     * @param batches       lotes ya enrutados por la fase greedy inicial
     * @param blockFlight   ocupación de vuelos del bloque tras la fase greedy
     * @param blockAirport  ídem para aeropuertos
     */
    public AlgorithmALNS(Graph graph,
                          GreedyRepairOperator enrutador,
                          List<LuggageBatch> batches,
                          Map<Long,Integer> blockFlight,
                          Map<Long,Integer> blockAirport) {
        this.graph    = graph;
        this.enrutador = enrutador;
        this.temperature = initialTemp;

        // Dos operadores de destroy — el adaptativo decide cuál usar en cada iter
        this.destroyOps = List.of(
                new CapacityDestroyOperator(graph),    // idx 0
                new WorstRouteDestroyOperator(graph)   // idx 1
        );

        int n = destroyOps.size();
        this.weights = new double[n];
        this.scores  = new double[n];
        this.uses    = new int[n];
        Arrays.fill(weights, 1.0); // pesos iniciales iguales → selección equiprobable

        // Solución inicial = resultado de la fase greedy
        this.currentSolution = new AlnsSolution(batches);
        this.currentFlight   = new HashMap<>(blockFlight);
        this.currentAirport  = new HashMap<>(blockAirport);

        // La solución greedy es la mejor conocida al arrancar
        this.bestSolution = currentSolution.cloneSolution();
        this.bestFlight   = new HashMap<>(currentFlight);
        this.bestAirport  = new HashMap<>(currentAirport);
    }

    // ────────────────────────────────────────────────────────────────────────

    public void run(int maxIterations) {
        if (currentSolution.getBatches().size() < minBlockSize) return;

        double bestCost    = bestSolution.calculateCost();
        double currentCost = bestCost;

        for (int iter = 0; iter < maxIterations && temperature > minTemp; iter++) {

            // ── 1. Selección adaptativa (ruleta proporcional a pesos) ────────
            int selectedIdx = selectDestroyOp();
            uses[selectedIdx]++;

            // ── 2. Clonar estado actual ───────────────────────────────────────
            AlnsSolution     candidate = currentSolution.cloneSolution();
            Map<Long,Integer> cFlight  = new HashMap<>(currentFlight);
            Map<Long,Integer> cAirport = new HashMap<>(currentAirport);

            // ── 3. Destrucción ────────────────────────────────────────────────
            List<LuggageBatch> unassigned =
                    destroyOps.get(selectedIdx).destroy(candidate, destroyFactor);

            // Liberar la capacidad de los lotes destruidos
            for (LuggageBatch b : unassigned)
                enrutador.releaseFromBlock(b, cFlight, cAirport);

            // ── 4. Reparación ─────────────────────────────────────────────────
            enrutador.repair(candidate, unassigned, cFlight, cAirport);

            // ── 5. Evaluación y aceptación SA ─────────────────────────────────
            double candidateCost = candidate.calculateCost();
            boolean accepted     = accept(currentCost, candidateCost, temperature);

            // ── 6. Recompensa al operador y actualización de estado ───────────
            double reward = 0;
            if (accepted) {
                boolean mejora = candidateCost < currentCost;

                currentSolution = candidate;
                currentFlight   = cFlight;
                currentAirport  = cAirport;
                currentCost     = candidateCost;

                if (candidateCost < bestCost) {
                    reward       = REWARD_NEW_BEST;
                    bestSolution = currentSolution.cloneSolution();
                    bestFlight   = new HashMap<>(currentFlight);
                    bestAirport  = new HashMap<>(currentAirport);
                    bestCost     = candidateCost;
                } else if (mejora) {
                    reward = REWARD_IMPROVEMENT;
                } else {
                    reward = REWARD_ACCEPTED;   // aceptado por SA pero no mejora
                }
            }
            scores[selectedIdx] += reward;

            // ── 7. Actualización periódica de pesos adaptativos ───────────────
            if ((iter + 1) % segmentLength == 0) updateWeights();

            // ── 8. Enfriamiento ───────────────────────────────────────────────
            temperature *= coolingRate;
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    /** Ruleta proporcional a los pesos actuales. */
    private int selectDestroyOp() {
        double total = 0;
        for (double w : weights) total += w;
        double rand = Math.random() * total;
        double cum  = 0;
        for (int i = 0; i < weights.length; i++) {
            cum += weights[i];
            if (rand <= cum) return i;
        }
        return weights.length - 1;
    }

    /**
     * Actualiza pesos según Ropke & Pisinger (2006):
     *   w_i ← (1 − r) · w_i + r · (score_i / max(1, uses_i))
     * Luego reinicia contadores del segmento.
     */
    private void updateWeights() {
        for (int i = 0; i < weights.length; i++) {
            if (uses[i] > 0)
                weights[i] = (1 - REACTION_FACTOR) * weights[i]
                           + REACTION_FACTOR * (scores[i] / uses[i]);
            scores[i] = 0;
            uses[i]   = 0;
        }
    }

    private boolean accept(double current, double candidate, double temp) {
        if (candidate <= current) return true;
        return Math.random() < Math.exp((current - candidate) / temp);
    }

    public AlnsSolution      getBestSolution()     { return bestSolution; }
    public Map<Long,Integer> getBestBlockFlight()  { return bestFlight;   }
    public Map<Long,Integer> getBestBlockAirport() { return bestAirport;  }
}
