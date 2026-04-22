package com.tasfb2b.planificador.algorithm.aco;

public class ConfigACO {
    public double alpha = 1.0;   // feromona
    public double beta = 2.0;    // heurística
    public double evaporation = 0.5;

    public int antCount = 20;
    public int iterations = 100;
}
