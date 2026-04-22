package com.tasfb2b.planificador.algorithm.aco;

import java.time.LocalDateTime;

public class Edge {

    public String id; // ID del vuelo para poder rastrearlo
    public Node from;
    public Node to;

    public double cost;      // Podría ser el tiempo de vuelo en minutos
    public double capacity;  // Capacidad máxima de maletas

    // Nuevos campos vitales para el enrutamiento de vuelos
    public LocalDateTime departureTime;
    public LocalDateTime arrivalTime;

    public double pheromone;
}