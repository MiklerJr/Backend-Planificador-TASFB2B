package com.tasfb2b.planificador.dto.almacenes;

import lombok.Data;

import java.util.List;

@Data
public class SerieAlmacenesResponse {
    private String jobId;
    private int desde;
    private int total;
    private boolean terminado;
    private List<SerieItem> series;

    @Data
    public static class SerieItem {
        private int bloqueIdx;
        private List<OcupacionAlmacenSlot> slots;
    }
}
