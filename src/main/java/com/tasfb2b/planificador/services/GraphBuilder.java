package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
import com.tasfb2b.planificador.model.Aeropuerto;

import java.util.List;

public class GraphBuilder {

    /**
     * formato de vuelos:
     * SKBO-SEQM-19:00-07:00-120
     */
    public Graph build(List<Aeropuerto> aeropuertos, List<String> flightLines) {

        Graph graph = new Graph();

        // 1. CREAR NODOS - aeropuertos 
        for (Aeropuerto a : aeropuertos) {

            Node node = new Node(a.getCodigo());
            node.lat = a.getLatitud();
            node.lon = a.getLongitud();
            graph.nodes.put(node.code, node);
        }

        // 2. CREAR ARISTAS (VUELOS)
        for (String line : flightLines) {

            if (line == null || line.isEmpty()) continue;

            String[] parts = line.split("-");

            if (parts.length < 5) continue;

            String origin = parts[0]; // codigio aeropuerto origen
            String destination = parts[1]; // codigo aeropuerto destino
            String departure = parts[2]; // hora salida : HH:MM
            String arrival = parts[3];  // hora llegada : HH:MM
            int capacity = parseCapacity(parts[4]);

            Node from = graph.nodes.get(origin); // extraemos nodos del grafo por codigo
            Node to = graph.nodes.get(destination);

            if (from == null || to == null) {
                continue;
            }

            double d = distance(from, to); // calculamos distancia entre aeropuertos

            Edge edge = new Edge();
            edge.from = from;
            edge.to = to;

            edge.departureTime = departure;
            edge.arrivalTime = arrival;
            edge.capacity = capacity;

            edge.cost = calculateCost(departure, arrival);

            graph.edges.add(edge);
        }

        return graph;
    }
    
    // CAPACIDAD (#### o números)
    private int parseCapacity(String value) {

        if (value == null) return 0;

        value = value.replace("#", "").trim();

        if (value.isEmpty()) return 0;

        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    // COSTO BASADO EN TIEMPO
    private double calculateCost(String dep, String arr) {

        int depMin = toMinutes(dep);
        int arrMin = toMinutes(arr);

        int diff = arrMin - depMin;

        // si cruza medianoche
        if (diff < 0) {
            diff += 24 * 60;
        }

        return diff;
    }

    // HH:MM → minutos
    private int toMinutes(String time) {

        if (time == null || !time.contains(":")) return 0;

        String[] parts = time.split(":");

        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);

        return h * 60 + m;
    }


    double distance(Node a, Node b) {
        // Haversine formula
        double R = 6371; // Earth radius in km
        double dLat = Math.toRadians(b.lat - a.lat);
        double dLon = Math.toRadians(b.lon - a.lon);
        double lat1 = Math.toRadians(a.lat);
        double lat2 = Math.toRadians(b.lat);

        double haversine = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return R * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }
}