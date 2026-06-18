package com.tasfb2b.planificador.dto;

import lombok.Data;

@Data
public class AeropuertoResponse {
    private String codigo;
    private String ciudad;
    private String pais;
    private Integer offsetHorario;
    private Integer capacidad;
    private Double latitud;
    private Double longitud;
    private boolean activo;
}
