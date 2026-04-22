package com.tasfb2b.planificador.algorithm.alns;

import java.util.ArrayList;
import java.util.List;
import com.tasfb2b.planificador.algorithm.aco.Graph;

import com.tasfb2b.planificador.algorithm.alns.DestroyOperator;
import com.tasfb2b.planificador.algorithm.alns.CapacityDestroyOperator;
import com.tasfb2b.planificador.algorithm.alns.RepairOperator;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;

public class AlgorithmALNS {

    private Graph graph; // Reutilizamos el grafo de aeropuertos y vuelos
    private AlnsSolution currentSolution;
    private AlnsSolution bestSolution;

    // Parámetros de Simulated Annealing y ALNS
    private double temperature = 100.0;
    private double coolingRate = 0.95;
    private double destroyFactor = 0.20; // 20% de destrucción

    // Operadores
    private List<DestroyOperator> destroyOperators;
    private List<RepairOperator> repairOperators;

    public AlgorithmALNS(Graph graph, List<LuggageBatch> initialBatches) {
        this.graph = graph;
        this.destroyOperators = new ArrayList<>();
        this.repairOperators = new ArrayList<>();

        // Inicializar solución base (vacía o con asignación voraz)
        this.currentSolution = new AlnsSolution(initialBatches);

        // Cargar operadores disponibles
        this.destroyOperators.add(new CapacityDestroyOperator(graph));
        this.repairOperators.add(new GreedyRepairOperator(graph));
    }

    public void run(int maxIterations) {
        this.bestSolution = this.currentSolution.cloneSolution();

        for (int i = 0; i < maxIterations; i++) {
            if (temperature <= 0.1) break;

            // 1. Seleccionar operadores (simplificado a elegir el primero por ahora)
            DestroyOperator destroyOp = destroyOperators.get(0);
            RepairOperator repairOp = repairOperators.get(0);

            // 2. Destrucción: Remover un porcentaje de asignaciones
            AlnsSolution candidateSolution = this.currentSolution.cloneSolution();
            List<LuggageBatch> unassigned = destroyOp.destroy(candidateSolution, destroyFactor);

            // 3. Reparación: Reinsertar evaluando UTC y 10 min de escala
            repairOp.repair(candidateSolution, unassigned);

            // 4. Evaluar y Aceptar (Simulated Annealing)
            double currentCost = currentSolution.calculateCost();
            double candidateCost = candidateSolution.calculateCost();

            if (accept(currentCost, candidateCost, temperature)) {
                this.currentSolution = candidateSolution;

                if (candidateCost < bestSolution.calculateCost() && candidateSolution.isValid(graph)) {
                    this.bestSolution = candidateSolution.cloneSolution();
                }
            }

            // 5. Enfriamiento
            temperature *= coolingRate;
        }
    }

    private boolean accept(double currentCost, double candidateCost, double temp) {
        if (candidateCost < currentCost) {
            return true;
        }
        return Math.random() < Math.exp((currentCost - candidateCost) / temp);
    }

    public AlnsSolution getBestSolution() {
        return bestSolution;
    }
}