package com.tasfb2b.planificador.dto.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Respuesta de {@code GET /jobs/activo}: reenganche en un solo round-trip para clientes que
 * recién se conectan (F5 u otra computadora). Si no hay simulación en curso solo va
 * {@code activo=false}; si la hay, lo mínimo para engancharse: el {@code jobId}, dónde va el
 * stream de bloques ({@code totalBloques}/{@code primerBloqueDisponible}) y el temporizador real.
 */
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
