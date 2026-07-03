package com.tasfb2b.planificador.algoritmo.grafo;

import jakarta.validation.constraints.NotBlank;
import java.util.*;

public class Grafo {

    public Map<String, Nodo> nodes = new HashMap<>();
    public List<Arista> edges = new ArrayList<>();

    private final Map<String, List<Arista>> adjList = new HashMap<>();

    public void addNode(@NotBlank(message = "El aeropuerto debe tener un codigo de identificación") String codigo) {
        if (!nodes.containsKey(codigo)) {
            nodes.put(codigo, new Nodo(codigo));
        }
    }

    public void addEdge(Arista edge) {
        edges.add(edge);
        if (edge.from != null) {
            adjList.computeIfAbsent(edge.from.code, k -> new ArrayList<>()).add(edge);
        }
    }

    public List<Arista> getNeighbors(String nodeId) {
        List<Arista> result = adjList.get(nodeId);
        return result != null ? result : Collections.emptyList();
    }

    public List<Arista> getEdgesFrom(String nodeCode) {
        return getNeighbors(nodeCode);
    }
}