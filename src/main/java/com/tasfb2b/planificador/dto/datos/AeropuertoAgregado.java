package com.tasfb2b.planificador.dto.datos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AeropuertoAgregado {
    private String icao;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String ciudad;
    private Integer husoHorario;
    private int capacidad;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String continente;
    private int bloqueIdx;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String motivo;
}
