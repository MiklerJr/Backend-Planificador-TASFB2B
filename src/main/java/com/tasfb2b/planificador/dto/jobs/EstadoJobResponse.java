package com.tasfb2b.planificador.dto.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

import com.tasfb2b.planificador.dto.datos.AeropuertoAgregado;
import com.tasfb2b.planificador.dto.vuelos.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.vuelos.VueloAgregado;
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String temporizadorInicioUtc;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long duracionRealMs;
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
    private List<VueloAgregado> vuelosAgregados;
    private List<VueloAgregado> altasVueloNoAplicadas;
    private List<AeropuertoAgregado> aeropuertosAgregados;
    private List<AeropuertoAgregado> altasAeropuertoNoAplicadas;
}
