package com.tasfb2b.planificador.dto.simulacion;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
public class Metricas {
    private int  procesadas;
    private int  enrutadas;
    private int  sinRuta;
    private int  cumpleSLA;
    private int  tardadas;
    private long maletasIndividuales;
    private int  vuelosCancelados;
    private long tiempoEjecucionMs;
    private boolean collapsoDetectado;
    private int     bloqueColapso;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String motivoColapso;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String detalleColapso;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String instanteColapsoUtc;

    private long taMinMs;
    private long taMaxMs;
    private long taPromedioMs;
    private long tiempoTotalAlgMs;
    private boolean advertenciaCalibracion;

    private int backlogActual;
    private int backlogPico;
    private int sinRutaDefinitivo;
}
