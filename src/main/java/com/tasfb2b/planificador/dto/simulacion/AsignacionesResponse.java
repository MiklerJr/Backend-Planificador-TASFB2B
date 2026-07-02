package com.tasfb2b.planificador.dto.simulacion;

import lombok.Data;

import java.util.List;

@Data
public class AsignacionesResponse {
    private String jobId;
    private int desde;
    private String aeropuerto;
    private String vueloId;
    private boolean soloEnrutadas;
    private int total;
    private List<AsignacionItem> asignaciones;

    @Data
    public static class AsignacionItem {
        private int bloqueIdx;
        private String horaInicio;
        private String horaFin;
        private AsignacionMaleta asignacion;
    }
}
