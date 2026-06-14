package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Estado y progreso de un job (Tanda 1B), expuesto por {@code GET /jobs/{id}/estado}. Antes el
 * cuerpo se construía a mano en el controller; ahora lo arma {@code PlanificadorService.getEstadoJob}.
 *
 * <p>Byte-compatible con el mapa anterior: {@code fechaInicio}, {@code fin}, {@code error} y
 * {@code alertaColapso} se omiten cuando son {@code null} ({@link JsonInclude.Include#NON_NULL});
 * {@code vuelosCancelados} se emite siempre (lista vacía si no hubo cancelaciones).
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
}
