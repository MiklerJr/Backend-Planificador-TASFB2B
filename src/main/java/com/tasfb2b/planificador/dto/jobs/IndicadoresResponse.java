package com.tasfb2b.planificador.dto.jobs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacenRow;
import com.tasfb2b.planificador.dto.vuelos.CargaVueloRow;

/**
 * Read model de indicadores del semáforo, expuesto por {@code GET /jobs/{id}/indicadores}:
 * umbrales verde/ámbar más la telemetría consolidada de vuelos y almacenes (las mismas filas de
 * {@code /vuelos/carga} y {@code /almacenes/ocupacion}).
 */
@Data
public class IndicadoresResponse {
    private String jobId;
    private Umbrales umbrales;
    private List<CargaVueloRow> vuelos;
    private List<OcupacionAlmacenRow> almacenes;

    /** Cortes del semáforo (fracción 0..1): verde ≤ {@code verdeHasta}, ámbar ≤ {@code ambarHasta}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Umbrales {
        private double verdeHasta;
        private double ambarHasta;
    }
}
