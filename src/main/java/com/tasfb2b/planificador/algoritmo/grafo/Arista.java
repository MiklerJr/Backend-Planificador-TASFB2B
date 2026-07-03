package com.tasfb2b.planificador.algoritmo.grafo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class Arista {

    public String id;
    public Nodo origen;
    public Nodo destino;
    public double costo;

    public int capacidad;
    public int capacidadUsada = 0;

    public LocalDateTime horaSalida;
    public LocalDateTime horaLlegada;
    public Duration      duracion;          // precomputado en MapeadorAlgoritmo (maneja medianoche)
    public LocalTime     horaSalidaLocal; // precomputado para evitar toLocalTime() en el bucle
    public int           minutoDelDiaSalida;     // hora*60+min, evita LocalTime en el loop caliente
    public int           duracionMinutos;    // duración en minutos, evita Duration en el loop caliente

    public int    indice;              // índice único asignado por MapeadorAlgoritmo (para claves long)
    public double feromona = 1.0;
    public double cacheHeuristica;

    public boolean tieneCapacidad(int demand) {
        return (capacidadUsada + demand) <= capacidad;
    }

    public void usarCapacidad(int demand) {
        this.capacidadUsada += demand;
    }
}
