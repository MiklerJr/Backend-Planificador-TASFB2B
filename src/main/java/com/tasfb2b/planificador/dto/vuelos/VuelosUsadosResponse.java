package com.tasfb2b.planificador.dto.vuelos;

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
        /** {@code vueloId|fechaSalida} con la salida en UTC — identifica el vuelo-día. */
        private String flightKey;
        private int bloqueIdx;
        private String horaInicio;
        private String horaFin;
        private String vueloId;
        private String origen;
        private String destino;
        /** Salida del vuelo-día en UTC (mismo eje que {@code TramoRuta.salidaUtc}). */
        private String fechaSalida;
        /** Llegada del vuelo-día en UTC. */
        private String fechaLlegada;
        private int cantidadMaletas;
        private int cantidadEnvios;
        private List<String> envioIds;
    }
}
