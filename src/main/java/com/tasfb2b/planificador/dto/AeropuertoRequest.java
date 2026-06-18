package com.tasfb2b.planificador.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AeropuertoRequest {
    @NotBlank
    private String codigo;
    @NotBlank
    private String ciudad;
    @NotBlank
    private String pais;
    @NotNull
    private Integer offsetHorario;
    @NotNull
    private Integer capacidad;
    @NotNull
    private Double latitud;
    @NotNull
    private Double longitud;
    private Boolean activo = true;
}
