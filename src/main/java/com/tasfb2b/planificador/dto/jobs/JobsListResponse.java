package com.tasfb2b.planificador.dto.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
public class JobsListResponse {
    private List<JobResumen> jobs;
    private int total;

    @Data
    public static class JobResumen {
        private String jobId;
        private String escenario;
        private String algoritmo;
        private String estado;
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
