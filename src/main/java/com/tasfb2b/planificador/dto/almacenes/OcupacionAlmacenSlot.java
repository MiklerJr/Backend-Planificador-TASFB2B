package com.tasfb2b.planificador.dto.almacenes;

import lombok.Data;

@Data
public class OcupacionAlmacenSlot {
    private String aeropuerto;
    private String hora;
    private int capacidadMaxima;
    private int ocupacion;
    private double porcentajeOcupacion;
    private String semaforo;
}
