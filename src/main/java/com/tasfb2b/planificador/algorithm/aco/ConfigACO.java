package com.tasfb2b.planificador.algorithm.aco;

public class ConfigACO {
    // Invertimos los pesos: priorizamos la memoria colectiva sobre el instinto inmediato
    public double alpha = 2.0;   // feromona (trabajo en equipo)
    public double beta = 1.0;    // heurística (menos "miedosas")

    // Bajamos la evaporación para que las rutas buenas perduren más tiempo en memoria
    public double evaporation = 0.15;
    public double initialPheromone = 1.0;
    public double q = 100.0;

    // Aumentamos el número de exploradoras para cubrir más opciones en la red colapsada
    public int antCount = 40;
    public int iterations = 100;

    // Activamos esto: Si en 20 iteraciones no encuentran una ruta mejor, se detienen.
    // Esto compensa el aumento de hormigas para que el algoritmo siga siendo rapidísimo.
    public int maxNoImprovement = 20;
}