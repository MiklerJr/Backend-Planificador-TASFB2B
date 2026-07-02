package com.tasfb2b.planificador.dto.simulacion;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
public class EstadoInicialResponse {
    private String jobId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String fechaInicio;
    private int total;
    private List<AsignacionMaleta> asignaciones;
}
