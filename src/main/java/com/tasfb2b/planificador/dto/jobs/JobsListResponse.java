package com.tasfb2b.planificador.dto.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Listado de jobs en memoria, expuesto por {@code GET /jobs}. Lo arma
 * {@code PlanificadorService.listarJobsResponse}.
 *
 * <p>Serialización: cada {@link JobResumen} omite {@code fechaInicio} y {@code fin} cuando son
 * {@code null} ({@link JsonInclude.Include#NON_NULL}).
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
        /**
         * E1 — Operación día a día: {@code true} si el job es la "caja registradora" en vivo (cursor
         * anclado a now() UTC, demanda solo por registro). El front lo usa para auto-detectar la
         * operación tras un refresh sin caer al filtro ambiguo por {@code escenario=="1"}.
         */
        private boolean enVivo;
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
