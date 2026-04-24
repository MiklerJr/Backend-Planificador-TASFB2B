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

    // Parámetros de simulación para el frontend
    // Sc = k * saMinutos → cuántos minutos de datos consume el frontend por tick visual
    private int k;           // factor de aceleración (K=1 día-a-día, K=14 sim-3días, K=75 colapso)
    private int saMinutos;   // tamaño de ventana del planificador (Sa)

    @Data
    public static class Metricas {
        private int  procesadas;           // número de envíos (LuggageBatch)
        private int  enrutadas;            // envíos con ruta asignada
        private int  sinRuta;              // envíos sin ruta
        private int  cumpleSLA;            // envíos enrutados dentro del plazo
        private int  tardadas;             // envíos enrutados fuera del plazo
        private long maletasIndividuales;  // suma de cantidades físicas (bag count real)
        private int  vuelosCancelados;     // número de combinaciones vuelo-día canceladas
        private long tiempoEjecucionMs;
        private boolean collapsoDetectado; // escenario 3: true si se detectó colapso
        private int     bloqueColapso;     // escenario 3: índice del bloque donde ocurrió (-1 si no)
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
        private boolean cumpleSLA;
        private List<String> rutaVuelos;
        /** Tramos con tiempos reales UTC; permite al frontend rastrear dónde está la maleta. */
        private List<TramoRuta> tramos;
    }

    @Data
    public static class TramoRuta {
        private String vueloId;
        private String origen;
        private String destino;
        private String salidaUtc;   // ISO datetime UTC del despegue real de este tramo
        private String llegadaUtc;  // ISO datetime UTC del aterrizaje real de este tramo
    }
}