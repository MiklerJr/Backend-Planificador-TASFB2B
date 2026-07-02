package com.tasfb2b.planificador.dto.simulacion;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
public class TramoRuta {
    private String vueloId;
    private String origen;
    private String destino;
    private String salidaUtc;
    private String llegadaUtc;
    private String salidaLocal;
    private String llegadaLocal;
    private int duracionMin;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String estado;
}
