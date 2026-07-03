package com.tasfb2b.planificador.algoritmo.grafo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class Arista {

    public String id; 
    public Nodo from;
    public Nodo to;
    public double cost;      
    
    public int capacity;
    public int usedCapacity = 0;

    public LocalDateTime departureTime;
    public LocalDateTime arrivalTime;
    public Duration      duration;          // precomputado en MapeadorAlgoritmo (maneja medianoche)
    public LocalTime     departureLocalTime; // precomputado para evitar toLocalTime() en el bucle
    public int           depMinuteOfDay;     // hora*60+min, evita LocalTime en el loop caliente
    public int           durationMinutes;    // duración en minutos, evita Duration en el loop caliente

    public int    idx;              // índice único asignado por MapeadorAlgoritmo (para claves long)
    public double pheromone = 1.0;
    public double heuristicCache;

    public boolean hasCapacity(int demand) {
        return (usedCapacity + demand) <= capacity;
    }

    public void useCapacity(int demand) {
        this.usedCapacity += demand;
    }
}