package com.tasfb2b.planificador.dto.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import com.tasfb2b.planificador.dto.simulacion.Metricas;

@Data
public class TableroResponse {
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
    private int bloqueActual;
    private int totalBloques;
    private int bloquesPublicados;
    private int posicionEnCola;
    private boolean canceladoPorUsuario;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
    private Metricas metricas;
    private Tasas tasas;
    private UltimoBloque ultimoBloque;

    @Data
    public static class Tasas {
        private double enrutamientoPct;
        private double sinRutaPct;
        private double cumpleSlaPct;
        private double tardadasPct;
    }

    @Data
    public static class UltimoBloque {
        private int bloqueIdx;
        private String horaInicio;
        private String horaFin;
        private int maletasProcesadas;
        private int maletasEnrutadas;
        private long maletasProcesadasAcum;
        private long maletasEnrutadasAcum;
        private long maletasEntregadasAcum;
        private long taMs;
        private int scMinutos;
    }
}
