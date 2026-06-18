package com.tasfb2b.planificador.dto;

import lombok.Data;

@Data
public class OcupacionAlmacen {
    private String aeropuerto;
    private String fecha;
    private int capacidadMaxima;
    private int ocupacionAsignada;
    private double porcentajeOcupacion;
    private String semaforo;
}
