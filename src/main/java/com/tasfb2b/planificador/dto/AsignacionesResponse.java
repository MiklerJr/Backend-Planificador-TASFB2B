package com.tasfb2b.planificador.dto;

import lombok.Data;

import java.util.List;

/**
 * Read model de asignaciones por envío con filtros opcionales (Tanda 1B), expuesto por
 * {@code GET /jobs/{id}/asignaciones}. {@code aeropuerto} y {@code vueloId} reflejan los filtros
 * aplicados (normalizados) y se emiten SIEMPRE, incluso {@code null} cuando no se filtró — igual que
 * el mapa anterior.
 */
@Data
public class AsignacionesResponse {
    private String jobId;
    private int desde;
    private String aeropuerto;
    private String vueloId;
    private boolean soloEnrutadas;
    private int total;
    private List<AsignacionItem> asignaciones;

    /** Una asignación con la posición del bloque que la publicó. */
    @Data
    public static class AsignacionItem {
        private int bloqueIdx;
        private String horaInicio;
        private String horaFin;
        private AsignacionMaleta asignacion;
    }
}
