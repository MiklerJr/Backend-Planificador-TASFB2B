package com.tasfb2b.planificador.algorithm.aco;

import jakarta.validation.constraints.NotBlank;
import java.util.*;

public class Graph {

    public Map<String, Node> nodes = new HashMap<>();
    public List<Edge> edges = new ArrayList<>();

    // Lista de adyacencia: evita escanear todas las aristas en cada llamada a getNeighbors.
    // Se construye incrementalmente en addEdge; getNeighbors pasa de O(E) a O(1)+O(grado).
    private final Map<String, List<Edge>> adjList = new HashMap<>();

    public void addNode(@NotBlank(message = "El aeropuerto debe tener un codigo de identificación") String codigo) {
        if (!nodes.containsKey(codigo)) {
            nodes.put(codigo, new Node(codigo));
        }
    }

    public void addEdge(Edge edge) {
        edges.add(edge);
        if (edge.from != null) {
            adjList.computeIfAbsent(edge.from.code, k -> new ArrayList<>()).add(edge);
        }
    }

    public List<Edge> getNeighbors(String nodeId) {
        List<Edge> result = adjList.get(nodeId);
        return result != null ? result : Collections.emptyList();
    }

    public List<Edge> getEdgesFrom(String nodeCode) {
        return getNeighbors(nodeCode);
    }
}