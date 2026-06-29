package com.tasfb2b.planificador.dto.almacenes;

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
