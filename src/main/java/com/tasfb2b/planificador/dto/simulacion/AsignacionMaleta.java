package com.tasfb2b.planificador.dto.simulacion;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    @JsonInclude(JsonInclude.Include.NON_NULL) private String  idEnvioPadre;
    @JsonInclude(JsonInclude.Include.NON_NULL) private Integer fragmento;
    @JsonInclude(JsonInclude.Include.NON_NULL) private Integer totalFragmentos;
}
