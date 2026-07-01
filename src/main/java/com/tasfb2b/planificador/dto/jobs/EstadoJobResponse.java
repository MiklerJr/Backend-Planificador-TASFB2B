package com.tasfb2b.planificador.dto.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

import com.tasfb2b.planificador.dto.vuelos.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.vuelos.VueloCancelado;

/**
 * Estado y progreso de un job, expuesto por {@code GET /jobs/{id}/estado}. Lo arma
 * {@code PlanificadorService.getEstadoJob}.
 *
 * <p>Serialización: {@code fechaInicio}, {@code fin}, {@code error} y
 * {@code alertaColapso} se omiten cuando son {@code null} ({@link JsonInclude.Include#NON_NULL});
 * {@code vuelosCancelados} se emite siempre (lista vacía si no hubo cancelaciones).
 *
 * <p>{@code cancelacionesNoAplicadas} (campo aditivo) lista las órdenes de cancelación que el motor
 * no pudo aplicar porque no casó ningún vuelo-día (trayecto inexistente o {@code fechaHoraSalida}
 * fuera del eje UTC esperado); también se emite siempre, vacía si no hubo ninguna.
 */
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
    /** Órdenes de cancelación que no casaron ningún vuelo-día (eje equivocado o trayecto inexistente). */
    private List<CancelacionVueloRequest> cancelacionesNoAplicadas;
    /** Envíos inyectados EN VIVO ya aplicados (liberados a la simulación), en orden de entrada. */
    private List<EnvioInyectadoInfo> enviosInyectados;
}
