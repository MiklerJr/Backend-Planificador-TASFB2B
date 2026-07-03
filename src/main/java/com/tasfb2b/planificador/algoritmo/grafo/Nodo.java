package com.tasfb2b.planificador.algoritmo.grafo;

import java.util.Objects;

public class Nodo {

    public String code;
    public double lat;
    public double lon;
    public int capacity; // capacidad máxima de almacén del aeropuerto
    public int idx = -1; // índice entero asignado por OperadorReparacionVoraz (evita lookups HashMap)

    public int storageCapacity;
    public int storageUsed = 0;

    public Nodo(String code) {
        this.code = code;
    }

    public Nodo(String code, double lat, double lon) {
        this.code = code;
        this.lat = lat;
        this.lon = lon;
    }

    public Nodo(String code, double lat, double lon, int storageCapacity) {
        this.code = code;
        this.storageCapacity = storageCapacity;
    }

    public boolean hasStorageCapacity(int cantidad) {
        return (storageUsed + cantidad) <= storageCapacity;
    }

    public void storeLoad(int cantidad) {
        this.storageUsed += cantidad;
    }

    public void releaseLoad(int cantidad) {
        this.storageUsed = Math.max(0, this.storageUsed - cantidad);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Nodo node)) return false;
        return Objects.equals(code, node.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}
