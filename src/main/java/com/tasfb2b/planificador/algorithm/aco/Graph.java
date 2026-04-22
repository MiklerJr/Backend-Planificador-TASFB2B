package com.tasfb2b.planificador.algorithm.aco;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Graph {

    public Map<String, Node> nodes = new HashMap<>();
    public List<Edge> edges = new ArrayList<>();

    public List<Edge> getEdgesFrom(String nodeCode) {
        return edges.stream()
                .filter(e -> e.from.code.equals(nodeCode))
                .toList();
    }

    public void addNode(@NotBlank(message = "El aeropuerto debe tener un codigo de identificación") String codigo) {
        // Solo lo agregamos si no existe previamente
        if (!nodes.containsKey(codigo)) {
            nodes.put(codigo, new Node(codigo));
        }
    }

    public void addEdge(Edge edge) {
        edges.add(edge);
    }

    public List<Edge> getNeighbors(String nodeId) {
        List<Edge> neighbors = new ArrayList<>();

        for (Edge edge : edges) {
            // Buscamos si el vuelo sale del nodo que nos están preguntando
            if (edge.from != null && edge.from.code.equals(nodeId)) {
                neighbors.add(edge);
            }
        }
        return neighbors;
    }
}