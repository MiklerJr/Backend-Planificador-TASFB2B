package com.tasfb2b.planificador.dto;

import java.util.Map;

public class ResumenPlanificacionGlobal {

    public int totalEnviosProcesados;
    public int totalEnviosExitosos;
    public int totalEnviosFallidos;
    public double costoPromedioExitosos;
    public int totalMaletas;
    public long tiempoEjecucionMs;
    public Map<String, EstadisticaOrigen> estadisticasPorOrigen;

    public ResumenPlanificacionGlobal() {}

    public static class EstadisticaOrigen {
        public String origen;
        public int totalEnvios;
        public int exitosos;
        public int fallidos;
        public int rutasDirectas;
        public int rutasConEscala;

        public EstadisticaOrigen() {}

        public EstadisticaOrigen(String origen, int total, int exito, int falla, int direct, int escala) {
            this.origen = origen;
            this.totalEnvios = total;
            this.exitosos = exito;
            this.fallidos = falla;
            this.rutasDirectas = direct;
            this.rutasConEscala = escala;
        }
    }
}