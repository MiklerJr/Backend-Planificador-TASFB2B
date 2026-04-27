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
    // Usado exclusivamente por AlgorithmACO. El ALNS usa flightOccupancy (HashMap en GreedyRepairOperator).
    public int usedCapacity = 0;

    public LocalDateTime departureTime;
    public LocalDateTime arrivalTime;
    public Duration      duration;          // precomputado en AlgorithmMapper (maneja medianoche)
    public LocalTime     departureLocalTime; // precomputado para evitar toLocalTime() en el bucle
    public int           depMinuteOfDay;     // hora*60+min, evita LocalTime en el loop caliente
    public int           durationMinutes;    // duración en minutos, evita Duration en el loop caliente

    public int    idx;              // índice único asignado por AlgorithmMapper (para claves long)
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
        
    public double getDuracionMinutos() {
        return CostFunction.calcularDuracionMinutos(this.departureTime, this.arrivalTime);
    }
}