package com.tasfb2b.planificador.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VueloRequest {
    @NotBlank
    private String id;
    @NotNull
    private Integer capacidad;
    @NotBlank
    private String origen;
    @NotBlank
    private String destino;
    @NotNull
    private LocalDateTime fechaHoraSalida;
    @NotNull
    private LocalDateTime fechaHoraLlegada;
}
