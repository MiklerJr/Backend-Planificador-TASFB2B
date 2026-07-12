package com.tasfb2b.planificador.dto.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobActivoResponse {
    private boolean activo;
    private String jobId;
    private String escenario;
    private String algoritmo;
    private String estado;
    private Boolean enVivo;
    private Double progreso;
    private Integer totalBloques;
    private Integer primerBloqueDisponible;
    private String temporizadorInicioUtc;
    private Long duracionRealMs;
}
