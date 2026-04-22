package com.tasfb2b.planificador.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SimulacionResponse {

    private Metricas metricas;
    private List<VueloBackend> vuelosPlaneados;
    private Map<String, AeropuertoDTO> aeropuertosInfo; // <- NUEVO MAPA

    @Data
    public static class Metricas {
        private int procesadas;
        private int enrutadas;
        private int sinRuta;
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

    // <- NUEVA CLASE INTERNA PARA EL AEROPUERTO
    @Data
    public static class AeropuertoDTO {
        private String codigo;
        private double latitud;
        private double longitud;
    }
}