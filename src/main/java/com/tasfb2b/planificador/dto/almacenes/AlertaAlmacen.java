package com.tasfb2b.planificador.dto.almacenes;

import lombok.Data;

@Data
public class AlertaAlmacen {
    private String nivel;
    private String almacenCritico;
    private int capacidadMaxima;
    private int ocupacion;
    private double porcentajeOcupacion;
    private int bloqueIdx;
}
