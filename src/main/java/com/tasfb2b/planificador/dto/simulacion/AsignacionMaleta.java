package com.tasfb2b.planificador.dto.simulacion;

import lombok.Data;
import java.util.List;

@Data
public class AsignacionMaleta {
    private String batchId;
    private String origen;
    private String destino;
    private int cantidad;
    private boolean enrutada;
    private boolean cumpleSLA;
    private List<String> rutaVuelos;
    private List<TramoRuta> tramos;
    private String registroLocal;
    private String registroUtc;
}
