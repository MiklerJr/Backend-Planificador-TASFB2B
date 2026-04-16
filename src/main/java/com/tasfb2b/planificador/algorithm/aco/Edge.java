package com.tasfb2b.planificador.algorithm.aco;

public class Edge {

    public Node from;
    public Node to;

    public double cost;

    public int capacity;
    public int usedCapacity = 0;

    public String departureTime;
    public String arrivalTime;

    public double pheromone = 1.0;

    public boolean hasCapacity(int demand) {
        return (usedCapacity + demand) <= capacity;
    }

    public void useCapacity(int demand) {
        this.usedCapacity += demand;
    }
}