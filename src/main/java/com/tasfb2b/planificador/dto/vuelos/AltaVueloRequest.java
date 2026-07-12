package com.tasfb2b.planificador.dto.vuelos;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AltaVueloRequest {
    private String origen;
    private String destino;
    private String horaSalida;
    private String horaLlegada;
    private int capacidad;
}
