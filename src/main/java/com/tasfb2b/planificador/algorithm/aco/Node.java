package com.tasfb2b.planificador.algorithm.aco;

import java.util.Objects;

public class Node {

    public String code;
    public double lat;
    public double lon;

    public Node(String code) {
        this.code = code;
    }

    // Constructor completo para cuando tienes las coordenadas disponibles
    public Node(String code, double lat, double lon) {
        this.code = code;
        this.lat = lat;
        this.lon = lon;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node node)) return false;
        return Objects.equals(code, node.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}
