package com.tasfb2b.planificador.dto.vuelos;

import lombok.Data;

@Data
public class CargaVueloFila {
    private String vueloId;
    private String origen;
    private String destino;
    private String fechaSalida;
    private String fechaLlegada;
    private int capacidadMaxima;
    private int cargaAsignada;
    private double porcentajeCarga;
    private String semaforo;
    private int bloqueIdx;
    private String horaInicio;
    private String horaFin;
}
