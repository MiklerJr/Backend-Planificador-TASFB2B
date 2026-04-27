package com.tasfb2b.planificador.algorithm.aco;

import java.util.Objects;

public class Node {

    public String code;
    public double lat;
    public double lon;
    public int capacity; // capacidad máxima de almacén del aeropuerto
    public int idx = -1; // índice entero asignado por GreedyRepairOperator (evita lookups HashMap)

    public int storageCapacity;
    public int storageUsed = 0;

    public Node(String code) {
        this.code = code;
    }

    public Node(String code, double lat, double lon) {
        this.code = code;
        this.lat = lat;
        this.lon = lon;

    public boolean hasStorageCapacity(int cantidad) {
        return (storageUsed + cantidad) <= storageCapacity;
    }

    public void storeLoad(int cantidad) {
        this.storageUsed += cantidad;
    }

    public void releaseLoad(int cantidad) {
        this.storageUsed = Math.max(0, this.storageUsed - cantidad);
    }

    public double getOcupacionAlmacen() {
        if (storageCapacity == 0) return 0.0;
        return (double) storageUsed / storageCapacity;
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
