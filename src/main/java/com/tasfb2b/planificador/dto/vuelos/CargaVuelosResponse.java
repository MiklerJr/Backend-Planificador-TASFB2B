package com.tasfb2b.planificador.dto.vuelos;

import lombok.Data;

import java.util.List;

@Data
public class CargaVuelosResponse {
    private String jobId;
    private int desde;
    private int proximoDesde;
    private boolean hayMas;
    private int bloquesPublicados;
    private boolean terminado;
    private int total;
    private List<CargaVueloFila> vuelos;
}
