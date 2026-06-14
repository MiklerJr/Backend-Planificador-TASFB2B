package com.tasfb2b.planificador.dto;

import lombok.Data;

@Data
public class VueloBackend {
    private String id;
    private String origen;
    private String destino;
    private String fechaSalida;
    private String fechaLlegada;
    private int capacidadMaxima;
    private int cargaAsignada;
}
