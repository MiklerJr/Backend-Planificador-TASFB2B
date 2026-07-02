package com.tasfb2b.planificador.dto.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

import com.tasfb2b.planificador.dto.vuelos.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.vuelos.VueloCancelado;

@Data
public class EstadoJobResponse {
    private String jobId;
    private String escenario;
    private String algoritmo;
    private long seed;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String fechaInicio;
    private int k;
    private String estado;
    private int bloqueActual;
    private int totalBloques;
    private double progreso;
    private int bloqueWarmup;
    private int totalBloquesWarmup;
    private double progresoWarmup;
    private int posicionEnCola;
    private boolean canceladoPorUsuario;
    private long taPromedioMs;
    private String inicio;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String fin;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AlertaColapso alertaColapso;
    private List<VueloCancelado> vuelosCancelados;
    private List<CancelacionVueloRequest> cancelacionesNoAplicadas;
    private List<EnvioInyectadoInfo> enviosInyectados;
}
