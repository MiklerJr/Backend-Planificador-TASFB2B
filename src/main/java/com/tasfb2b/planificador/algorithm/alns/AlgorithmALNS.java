package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.grafo.Graph;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
public class AlgorithmALNS {

    // ── Hiperparámetros ──────────────────────────────────────────────────────
    public double destroyFactor = 0.20;   // fracción de lotes destruidos por iter
    public double initialTemp   = 500.0;  // temperatura SA inicial
    public double coolingRate   = 0.85;   // factor de enfriamiento por iteración
    public double minTemp       = 0.1;    // temperatura mínima (parada anticipada)
    public int    minBlockSize  = 3;      // bloques menores no ejecutan ALNS
    public int    segmentLength = 3;      // iters entre actualizaciones de pesos

    public long   tiempoLimiteMs = Long.MAX_VALUE;

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

    private Random rng = new Random();

    public void setRandom(Random rng) {
        if (rng == null) return;
        this.rng = rng;
        if (destroyOps != null) {
            for (DestroyOperator op : destroyOps) op.setRandom(rng);
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    public AlgorithmALNS(Graph graph,
                          GreedyRepairOperator enrutador,
                          List<LuggageBatch> batches,
                          Map<Long,Integer> blockFlight,
                          Map<Long,Integer> blockAirport) {
        this(graph, enrutador, batches, blockFlight, blockAirport, null);
    }

    public AlgorithmALNS(Graph graph,
                          GreedyRepairOperator enrutador,
                          List<LuggageBatch> batches,
                          Map<Long,Integer> blockFlight,
                          Map<Long,Integer> blockAirport,
                          PlanificadorProperties props) {
        // 1. Aplicar config externa si está disponible (antes de usar initialTemp).
        if (props != null) {
            PlanificadorProperties.Alns a = props.getAlns();
            this.destroyFactor  = a.getDestroyFactor();
            this.initialTemp    = a.getInitialTemp();
            this.coolingRate    = a.getCoolingRate();
            this.minTemp        = a.getMinTemp();
            this.minBlockSize   = a.getMinBlockSize();
            this.segmentLength  = a.getSegmentLength();
        }

        this.graph       = graph;
        this.enrutador   = enrutador;
        this.temperature = initialTemp;

        if (props != null && props.getAlns().getOperadoresDestroy() != null
                && !props.getAlns().getOperadoresDestroy().isEmpty()) {
            this.destroyOps = construirOperadoresDestroy(props.getAlns().getOperadoresDestroy(), graph);
        } else {
            this.destroyOps = List.of(
                    new CapacityDestroyOperator(graph),
                    new WorstRouteDestroyOperator(graph)
            );
        }

        int n = destroyOps.size();
        this.weights = new double[n];
        this.scores  = new double[n];
        this.uses    = new int[n];
        Arrays.fill(weights, 1.0);

        if (props != null) {
            this.currentSolution = new AlnsSolution(batches,
                    props.getObjetivo().getPesoTransit(),
                    props.getObjetivo().getPesoTarde(),
                    props.getObjetivo().getPesoUsoAlmacen());
        } else {
            this.currentSolution = new AlnsSolution(batches);
        }
        this.currentFlight   = new HashMap<>(blockFlight);
        this.currentAirport  = new HashMap<>(blockAirport);

        // La solución greedy es la mejor conocida al arrancar
        this.bestSolution = currentSolution.cloneSolution();
        this.bestFlight   = new HashMap<>(currentFlight);
        this.bestAirport  = new HashMap<>(currentAirport);
    }

    public void run(int maxIterations) {
        if (currentSolution.getBatches().size() < minBlockSize) return;

        double bestCost    = bestSolution.calculateCost();
        double currentCost = bestCost;
        long   tInicio     = System.nanoTime();

        for (int iter = 0; iter < maxIterations && temperature > minTemp; iter++) {

            // Presupuesto de tiempo: aborta si excede tiempoLimiteMs.
            if (tiempoLimiteMs < Long.MAX_VALUE) {
                long elapsedMs = (System.nanoTime() - tInicio) / 1_000_000;
                if (elapsedMs >= tiempoLimiteMs) {
                    log.warn("ALNS abortado por presupuesto de tiempo: iter {}/{} ({}ms >= {}ms)",
                            iter, maxIterations, elapsedMs, tiempoLimiteMs);
                    break;
                }
            }


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

            for (LuggageBatch b : unassigned) {
                enrutador.releaseFromBlock(b, cFlight, cAirport);
                b.clearRoute();
            }

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

    private int selectDestroyOp() {
        double total = 0;
        for (double w : weights) total += w;
        double rand = rng.nextDouble() * total;
        double cum  = 0;
        for (int i = 0; i < weights.length; i++) {
            cum += weights[i];
            if (rand <= cum) return i;
        }
        return weights.length - 1;
    }

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
        return rng.nextDouble() < Math.exp((current - candidate) / temp);
    }

    public AlnsSolution      getBestSolution()     { return bestSolution; }
    public Map<Long,Integer> getBestBlockFlight()  { return bestFlight;   }
    public Map<Long,Integer> getBestBlockAirport() { return bestAirport;  }

    private static List<DestroyOperator> construirOperadoresDestroy(List<String> nombres, Graph graph) {
        java.util.ArrayList<DestroyOperator> ops = new java.util.ArrayList<>(nombres.size());
        for (String n : nombres) {
            switch (n.toLowerCase().trim()) {
                case "capacity"           -> ops.add(new CapacityDestroyOperator(graph));
                case "worst-route"        -> ops.add(new WorstRouteDestroyOperator(graph));
                case "random"             -> ops.add(new RandomDestroyOperator(graph));
                case "airport-congestion" -> ops.add(new AirportCongestionDestroyOperator(graph));
                default -> log.warn("Operador destroy desconocido en config: '{}' (ignorado)", n);
            }
        }
        if (ops.isEmpty()) {
            log.warn("Lista de operadores destroy vacía tras parseo — usando defaults");
            ops.add(new CapacityDestroyOperator(graph));
            ops.add(new WorstRouteDestroyOperator(graph));
        }
        return ops;
    }
}
