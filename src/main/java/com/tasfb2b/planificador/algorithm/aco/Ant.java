package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Ant {

    public List<Node> path = new ArrayList<>();
    public List<Edge> edgesPath = new ArrayList<>();
    public double totalCost = 0;

    public int load; //  paquetes que transporta

    //Reloj interno de la hormiga
    public java.time.LocalDateTime horaLlegadaActual = null;

    // Espejo de `path` como Set para que visited() sea O(1). Se mantiene
    // sincronizado vía addNode/reset; quien lea path crudo no lo nota.
    private final Set<Node> visitedSet = new HashSet<>();

    public void reset() {
        path.clear();
        edgesPath.clear();
        visitedSet.clear();
        totalCost = 0;
        horaLlegadaActual = null; // Reiniciar reloj
    }

    public void addNode(Node n) {
        path.add(n);
        visitedSet.add(n);
    }

    public boolean visited(Node n) {
        return visitedSet.contains(n);
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