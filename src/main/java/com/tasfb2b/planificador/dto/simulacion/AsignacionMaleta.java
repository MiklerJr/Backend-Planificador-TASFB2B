package com.tasfb2b.planificador.dto.simulacion;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.util.List;

@Data
public class AsignacionMaleta {
    private String batchId;              // fragmentado: id del sub-lote "<idEnvio>-F<n>"
    private String origen;
    private String destino;
    private int cantidad;                // fragmentado: maletas de ESTE sub-lote
    private boolean enrutada;
    private boolean cumpleSLA;
    private List<String> rutaVuelos;
    private List<TramoRuta> tramos;
    private String registroLocal;
    private String registroUtc;

    // Fragmentación (caso E1: cantidad > capacidad de avión). Sólo presentes si el envío se fragmentó;
    // NON_NULL campo a campo (NO a nivel de clase: eso suprimiría nulls ya existentes del contrato). Un
    // envío no fragmentado serializa byte-idéntico a hoy.
    @JsonInclude(JsonInclude.Include.NON_NULL) private String  idEnvioPadre;    // id del envío padre
    @JsonInclude(JsonInclude.Include.NON_NULL) private Integer fragmento;       // 1..N
    @JsonInclude(JsonInclude.Include.NON_NULL) private Integer totalFragmentos; // N
}
