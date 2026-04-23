package com.tasfb2b.planificador.algorithm.aco;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Edge {

    public String id; 
    public Node from;
    public Node to;
    public double cost;      
    
    public int capacity;  
    public int usedCapacity = 0;

    public LocalDateTime departureTime;
    public LocalDateTime arrivalTime;
    public Duration      duration;     // precomputado en AlgorithmMapper (maneja medianoche)
    public LocalTime     departureLocalTime; // precomputado para evitar toLocalTime() en el bucle

    public double pheromone = 1.0;

    public boolean hasCapacity(int demand) {
        return (usedCapacity + demand) <= capacity;
    }

    public void useCapacity(int demand) {
        this.usedCapacity += demand;
    }

    public String getDepartureTimeString() {
        return departureTime != null ? departureTime.toString() : "";
    }

    public String getArrivalTimeString() {
        return arrivalTime != null ? arrivalTime.toString() : "";
    }
}