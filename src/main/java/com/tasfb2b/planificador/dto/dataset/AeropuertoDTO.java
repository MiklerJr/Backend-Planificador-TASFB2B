package com.tasfb2b.planificador.dto.dataset;

import lombok.Data;

@Data
public class AeropuertoDTO {
    private String codigo;
    private double latitud;
    private double longitud;
    private Integer capacidadAlmacen;
    private Double gmt;
}
