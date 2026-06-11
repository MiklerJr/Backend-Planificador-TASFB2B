package com.tasfb2b.planificador.dto;

import lombok.Data;

import java.util.List;

@Data
public class VuelosUsadosResponse {
    private String jobId;
    private int desde;
    private int bloquesPublicados;
    private boolean terminado;
    private int total;
    private List<VueloUsado> vuelos;

    @Data
    public static class VueloUsado {
        private String flightKey;
        private int bloqueIdx;
        private String horaInicio;
        private String horaFin;
        private String vueloId;
        private String origen;
        private String destino;
        private String fechaSalida;
        private String fechaLlegada;
        private int cantidadMaletas;
        private int cantidadEnvios;
        private List<String> envioIds;
    }
}
