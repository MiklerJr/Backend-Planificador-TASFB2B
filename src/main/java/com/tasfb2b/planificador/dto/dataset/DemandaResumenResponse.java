package com.tasfb2b.planificador.dto.dataset;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DemandaRow {
        private String clave;
        private long envios;
        private long maletas;
    }
}
