package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Graph {

    public Map<String, Node> nodes = new HashMap<>();
    public List<Edge> edges = new ArrayList<>();

    public List<Edge> getNeighbors(String nodeCode) {
        return edges.stream()
                .filter(e -> e.from.code.equals(nodeCode))
                .toList();
    }
}