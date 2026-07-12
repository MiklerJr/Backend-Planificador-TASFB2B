package com.tasfb2b.planificador.algoritmo.grafo;

import java.util.Objects;

public class Nodo {

    public String codigo;
    public double lat;
    public double lon;
    public int capacidad;
    public int indice = -1;

    public int capacidadAlmacen;
    public int ocupacionAlmacen = 0;

    public Nodo(String codigo) {
        this.codigo = codigo;
    }

    public Nodo(String codigo, double lat, double lon) {
        this.codigo = codigo;
        this.lat = lat;
        this.lon = lon;
    }

    public Nodo(String codigo, double lat, double lon, int capacidadAlmacen) {
        this.codigo = codigo;
        this.capacidadAlmacen = capacidadAlmacen;
    }

    public boolean tieneCapacidadAlmacen(int cantidad) {
        return (ocupacionAlmacen + cantidad) <= capacidadAlmacen;
    }

    public void ocupar(int cantidad) {
        this.ocupacionAlmacen += cantidad;
    }

    public void liberar(int cantidad) {
        this.ocupacionAlmacen = Math.max(0, this.ocupacionAlmacen - cantidad);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Nodo node)) return false;
        return Objects.equals(codigo, node.codigo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(codigo);
    }
}
