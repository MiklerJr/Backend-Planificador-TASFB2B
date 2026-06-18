package com.tasfb2b.planificador.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VueloResponse {
    private String id;
    private Integer capacidad;
    private String origen;
    private String destino;
    private LocalDateTime fechaHoraSalida;
    private LocalDateTime fechaHoraLlegada;
}
