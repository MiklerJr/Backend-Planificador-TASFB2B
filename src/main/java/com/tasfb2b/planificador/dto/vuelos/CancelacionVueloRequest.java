package com.tasfb2b.planificador.dto.vuelos;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class CancelacionVueloRequest {
    private String origen;
    private String destino;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime fechaHoraSalida;
}
