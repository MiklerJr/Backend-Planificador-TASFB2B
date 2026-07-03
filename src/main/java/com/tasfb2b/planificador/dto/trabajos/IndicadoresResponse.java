package com.tasfb2b.planificador.dto.trabajos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacenFila;
import com.tasfb2b.planificador.dto.vuelos.CargaVueloFila;

@Data
public class IndicadoresResponse {
    private String jobId;
    private Umbrales umbrales;
    private List<CargaVueloFila> vuelos;
    private List<OcupacionAlmacenFila> almacenes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Umbrales {
        private double verdeHasta;
        private double ambarHasta;
    }
}
