package com.tasfb2b.planificador.algorithm.aco;

import java.util.ArrayList;
import java.util.List;

public class Ant {

    public List<Node> path = new ArrayList<>();
    public double totalCost = 0;

    public int load; //  paquetes que transporta

    public void reset() {
        path.clear();
        totalCost = 0;
    }

    public boolean visited(Node n) {
        return path.contains(n);
    }
}