package com.tasfb2b.planificador.dto.dataset;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Demanda agregada del dataset en una ventana (Tanda 1B), expuesta por {@code GET /demanda/resumen}.
 * No requiere job. {@code desde}/{@code hasta} se emiten siempre (incluso {@code null} si el dataset
 * está vacío). Las listas top-N van vacías ({@code []}) cuando la ventana es inválida.
 */
@Data
public class DemandaResumenResponse {
    private String desde;
    private String hasta;
    private int top;
    private int totalEnvios;
    private long totalMaletas;
    private List<DemandaRow> porOrigen;
    private List<DemandaRow> porDestino;
    @JsonProperty("porOD")
    private List<DemandaRow> porOD;

    /** Fila agregada de demanda: clave (origen, destino u OD), nº de envíos y de maletas. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DemandaRow {
        private String clave;
        private long envios;
        private long maletas;
    }
}
