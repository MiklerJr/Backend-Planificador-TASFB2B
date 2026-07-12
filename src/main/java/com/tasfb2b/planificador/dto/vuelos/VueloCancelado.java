package com.tasfb2b.planificador.dto.vuelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class VueloCancelado {
    private String origen;
    private String destino;
    private LocalDateTime fechaHoraSalida;
    private int enviosAfectados;
}
