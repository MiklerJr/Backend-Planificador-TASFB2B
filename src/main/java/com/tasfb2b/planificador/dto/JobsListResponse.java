package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Listado de jobs en memoria (Tanda 1B), expuesto por {@code GET /jobs}. Antes el bucle se construía
 * a mano en el controller; ahora lo arma {@code PlanificadorService.listarJobsResponse}.
 *
 * <p>Byte-compatible con el mapa anterior: cada {@link JobResumen} omite {@code fechaInicio} y
 * {@code fin} cuando son {@code null} ({@link JsonInclude.Include#NON_NULL}).
 */
@Data
public class JobsListResponse {
    private List<JobResumen> jobs;
    private int total;

    /** Resumen de un job para el listado/re-enganche tras un refresh del front. */
    @Data
    public static class JobResumen {
        private String jobId;
        private String escenario;
        private String algoritmo;
        private String estado;
        private int k;
        private long seed;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String fechaInicio;
        private String inicio;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String fin;
        private double progreso;
        private double progresoWarmup;
    }
}
