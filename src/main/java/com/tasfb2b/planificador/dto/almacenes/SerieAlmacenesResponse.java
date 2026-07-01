package com.tasfb2b.planificador.dto.almacenes;

import lombok.Data;

import java.util.List;

/**
 * Serie temporal de ocupación de almacenes por SLOT de 60 min (eje UTC), una serie por bloque
 * publicado, expuesta por {@code GET /jobs/{id}/almacenes/serie?desde=N}. La construye
 * {@code PlanificadorService.getSerieAlmacenes}.
 * Misma paginación que {@code /bloques}: {@code desde} = índice de bloque.
 */
@Data
public class SerieAlmacenesResponse {
    private String jobId;
    private int desde;
    private int total;
    private boolean terminado;
    private List<SerieItem> series;

    /** Una serie de slots alineada con su índice de bloque. */
    @Data
    public static class SerieItem {
        private int bloqueIdx;
        private List<OcupacionAlmacenSlot> slots;
    }
}
