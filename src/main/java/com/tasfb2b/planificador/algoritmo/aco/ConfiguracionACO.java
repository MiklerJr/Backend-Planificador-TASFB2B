package com.tasfb2b.planificador.algoritmo.aco;

public class ConfiguracionACO {
    public double alpha = 2.0;   // peso de la feromona (α)
    public double beta = 1.0;    // peso de la heurística (β)
    public double evaporation = 0.15;
    public double initialPheromone = 1.0;
    public double q = 100.0;
    public int antCount = 40;
    public int iterations = 100;
    public int maxNoImprovement = 20;
}
