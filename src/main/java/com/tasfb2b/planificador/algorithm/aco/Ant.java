package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.List;

public class Ant {

    public List<Node> path = new ArrayList<>();
    public List<Edge> edgesPath = new ArrayList<>();
    public double totalCost = 0;

    public int load; //  paquetes que transporta

    public void reset() {
        path.clear();
        edgesPath.clear();
        totalCost = 0;
    }

    public boolean visited(Node n) {
        return path.contains(n);
    }

    public String getRutaStr() {
        if (path.isEmpty()) return "sin ruta";
        return path.stream()
                .map(n -> n.code)
                .reduce((a, b) -> a + " → " + b)
                .orElse("sin ruta");
    }

    @Override
    public String toString() {
        return "Ant{costo=" + totalCost + ", ruta=" + getRutaStr() + "}";
    }
}