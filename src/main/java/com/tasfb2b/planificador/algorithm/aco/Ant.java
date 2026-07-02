package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.grafo.Edge;
import com.tasfb2b.planificador.algorithm.grafo.Node;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;

public class Ant {

    public List<Node> path = new ArrayList<>();
    public List<Edge> edgesPath = new ArrayList<>();
    public double totalCost = 0;

    public int load; //  paquetes que transporta

    //Reloj interno de la hormiga
    public java.time.LocalDateTime horaLlegadaActual = null;

    private final BitSet visitedIdx = new BitSet();
    private HashSet<Node> visitedFallback;

    public void reset() {
        path.clear();
        edgesPath.clear();
        visitedIdx.clear();
        if (visitedFallback != null) visitedFallback.clear();
        totalCost = 0;
        horaLlegadaActual = null;
    }

    public void addNode(Node n) {
        path.add(n);
        if (n.idx >= 0) {
            visitedIdx.set(n.idx);
        } else {
            if (visitedFallback == null) visitedFallback = new HashSet<>();
            visitedFallback.add(n);
        }
    }

    public boolean visited(Node n) {
        if (n.idx >= 0) return visitedIdx.get(n.idx);
        return visitedFallback != null && visitedFallback.contains(n);
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
