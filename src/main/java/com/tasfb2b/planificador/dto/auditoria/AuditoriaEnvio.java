package com.tasfb2b.planificador.dto.auditoria;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditoriaEnvio {
    private String  idEnvio;
    private String  origen;
    private String  destino;
    private Integer clienteId;
    private int     cantidad;
    private String  tipoEnvio;
    private String  registroHHMM;
    private int     deadlineMin;
    private boolean exitoso;
    private String  motivoFalla;
    private String  ruta;
    private int     numTramos;
    private int     numEscalas;
    private int     tiempoVueloMin;
    private int     tiempoEsperaMin;
    private int     tiempoTotalMin;
    private int     llegadaMin;
    private int     slackSlaMin;
    private double  slackSlaHoras;
    private boolean cumpleSLA;
    private boolean sinCiclos;
    private boolean escalaMinOK;
    private int     scoreCalidad;
    private LocalDateTime fechaHoraInicio;
    private LocalDateTime fechaHoraFin;
}
