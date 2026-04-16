package com.tasfb2b.planificador.algorithm.aco;

public class Edge {

    public Node from;
    public Node to;

    public double cost;      // tiempo o distancia
    public double capacity;  // carga máxima

    public double pheromone;
}