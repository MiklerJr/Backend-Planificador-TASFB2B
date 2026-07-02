package com.tasfb2b.planificador.dto.almacenes;

import lombok.Data;

import java.util.List;

@Data
public class OcupacionAlmacenesResponse {
    private String jobId;
    private int desde;
    private int proximoDesde;
    private boolean hayMas;
    private int bloquesPublicados;
    private boolean terminado;
    private int total;
    private List<OcupacionAlmacenRow> almacenes;
}
