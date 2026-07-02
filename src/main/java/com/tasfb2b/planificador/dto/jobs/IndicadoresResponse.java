package com.tasfb2b.planificador.dto.jobs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacenRow;
import com.tasfb2b.planificador.dto.vuelos.CargaVueloRow;

@Data
public class IndicadoresResponse {
    private String jobId;
    private Umbrales umbrales;
    private List<CargaVueloRow> vuelos;
    private List<OcupacionAlmacenRow> almacenes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Umbrales {
        private double verdeHasta;
        private double ambarHasta;
    }
}
