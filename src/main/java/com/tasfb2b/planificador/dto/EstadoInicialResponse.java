package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Snapshot del ESTADO INICIAL de un job con warm-up (Tanda 1B), expuesto por
 * {@code GET /jobs/{id}/estado-inicial}: las asignaciones pre-calculadas cuyos envíos siguen activos
 * al llegar a {@code fechaInicio}. Antes el cuerpo se armaba a mano en el controller; ahora lo
 * construye {@code PlanificadorService.buildEstadoInicialResponse}.
 *
 * <p>{@code fechaInicio} se omite cuando es {@code null} ({@link JsonInclude.Include#NON_NULL}), igual
 * que el mapa anterior; {@code asignaciones} va vacía si el job no tuvo warm-up.
 */
@Data
public class EstadoInicialResponse {
    private String jobId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String fechaInicio;
    private int total;
    private List<AsignacionMaleta> asignaciones;
}
