package com.tasfb2b.planificador.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SimulacionResponse {

    private Metricas metricas;
    private int totalBloques;
    private List<VueloBackend> vuelosPlaneados;
    private Map<String, AeropuertoDTO> aeropuertosInfo;

    @Data
    public static class Metricas {
        private int procesadas;
        private int enrutadas;
        private int sinRuta;
        private long tiempoEjecucionMs;
    }

    @Data
    public static class VueloBackend {
        private String id;
        private String origen;
        private String destino;
        private String fechaSalida;
        private String fechaLlegada;
        private int capacidadMaxima;
        private int cargaAsignada;
    }

    @Data
    public static class AeropuertoDTO {
        private String codigo;
        private double latitud;
        private double longitud;
    }

    @Data
    public static class BloqueSimulacion {
        private String horaInicio;
        private String horaFin;
        private int maletasProcesadas;
        private int maletasEnrutadas;
        private List<AsignacionMaleta> asignaciones;
    }

    @Data
    public static class AsignacionMaleta {
        private String batchId;
        private String origen;
        private String destino;
        private int cantidad;
        private boolean enrutada;
        private List<String> rutaVuelos;
    }
}