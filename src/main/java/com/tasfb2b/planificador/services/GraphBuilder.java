package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class GraphBuilder {

    /**
     * formato de vuelos:
     * SKBO-SEQM-19:00-07:00-120
     */
    public Graph build(List<Aeropuerto> aeropuertos, List<String> flightLines) {

        Graph graph = new Graph();

        // Mapa código ICAO → offset horario (GMT), para normalizar los vuelos a UTC igual que
        // AlgorithmMapper (el cruce de medianoche debe decidirse con husos, no en hora local).
        Map<String, Integer> offsetPorCodigo = new HashMap<>();
        for (Aeropuerto a : aeropuertos) {
            if (a.getCodigo() != null && a.getOffsetHorario() != null) {
                offsetPorCodigo.put(a.getCodigo(), a.getOffsetHorario());
            }
        }

        // 1. CREAR NODOS - aeropuertos
        for (Aeropuerto a : aeropuertos) {
            Node node = new Node(a.getCodigo());
            node.lat = a.getLatitud() != null ? a.getLatitud() : 0.0;
            node.lon = a.getLongitud() != null ? a.getLongitud() : 0.0;
            node.storageCapacity = a.getCapacidad() != null ? a.getCapacidad() : 500;
            graph.nodes.put(node.code, node);
        }

        // 2. CREAR ARISTAS (VUELOS)
        for (String line : flightLines) {
            if (line == null || line.isEmpty()) continue;

            // limpiar formato tipo "//SKBO-SEQM-..."
            line = line.replace("//", "").trim();
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

            Edge edge = new Edge();
            edge.from = from;
            edge.to = to;

            // Los archivos de vuelos traen solo HH:MM (hora local de cada aeropuerto). Se normaliza
            // a UTC con los husos y un único módulo 24h, igual que AlgorithmMapper, para no inflar la
            // duración de vuelos hacia el oeste más cortos que su diferencia de huso.
            int origenOffset = offsetPorCodigo.getOrDefault(origin, 0);
            int destOffset   = offsetPorCodigo.getOrDefault(destination, 0);
            int depWall = toMinutes(departure);
            int arrWall = toMinutes(arrival);
            int depUtcMin = Math.floorMod(depWall - origenOffset * 60, 1440);
            int durMin = Math.floorMod((arrWall - destOffset * 60) - (depWall - origenOffset * 60), 1440);

            LocalDate fechaBase = LocalDate.of(2026, 1, 1);
            LocalDateTime depUtc = LocalDateTime.of(fechaBase, LocalTime.MIDNIGHT).plusMinutes(depUtcMin);
            edge.departureTime = depUtc;
            edge.arrivalTime = depUtc.plusMinutes(durMin);
            edge.capacity = capacity;
            edge.cost = durMin;
            edge.durationMinutes = durMin;
            edge.depMinuteOfDay = depUtcMin;

            graph.addEdge(edge);
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

    // HH:MM → minutos
    private int toMinutes(String time) {
        if (time == null || !time.contains(":")) return 0;
        String[] parts = time.split(":");
        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        return h * 60 + m;
    }
}