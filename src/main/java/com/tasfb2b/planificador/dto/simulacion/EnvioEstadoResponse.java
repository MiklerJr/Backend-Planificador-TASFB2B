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

    // Sólo cuando se consulta un envío FRAGMENTADO por su id de padre: el estado de cada sub-lote.
    // Null (omitido) para un envío no fragmentado ⇒ respuesta byte-idéntica a hoy. En la respuesta
    // agregada, {@code asignacion} va null (cada fragmento lleva la suya) y los contadores son sumas.
    private List<EnvioEstadoResponse> fragmentos;
}
