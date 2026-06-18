package com.tasfb2b.planificador.dto;

import lombok.Data;

@Data
public class AeropuertoDTO {
    private String codigo;
    private double latitud;
    private double longitud;
    /** Capacidad real de almacen del aeropuerto, en maletas individuales. */
    private Integer capacidadAlmacen;
}
