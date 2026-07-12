package com.tasfb2b.planificador.dto.simulacion;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnvioEstadoResponse {
    private AsignacionMaleta asignacion;
    private String estado;
    private String instanteReferencia;
    private boolean instanteDerivadoDelJob;
    private String ubicacionActual;
    private Integer tramoActualIdx;
    private int tramosCompletados;
    private int tramosTotales;
    private String llegadaFinalUtc;

    private List<EnvioEstadoResponse> fragmentos;
}
