package com.tasfb2b.planificador.dto.datos;

import lombok.Data;

@Data
public class AeropuertoDTO {
    private String codigo;
    private double latitud;
    private double longitud;
    private Integer capacidadAlmacen;
    private Integer capacidadAlmacenOriginal;
    private Double gmt;
}
