package com.tasfb2b.planificador.dto.vuelos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VueloAgregado {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String idVuelo;
    private String origen;
    private String destino;
    private String horaSalida;
    private String horaLlegada;
    private int capacidad;
    private int bloqueIdx;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String motivo;
}
