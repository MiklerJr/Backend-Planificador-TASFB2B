package com.tasfb2b.planificador.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registro de auditoría por envío para validación formal de restricciones del cliente.
 *
 * <p>Cada fila corresponde a un {@code LuggageBatch} procesado por el planificador.
 * Las columnas booleanas (cumpleSLA, sinCiclos, sinDirecto, escalaMinOK,
 * capacidadVuelosOK, almacenDestinoOK) permiten verificar de forma independiente
 * que el algoritmo respeta cada restricción del problema TASF.B2B.
 *
 * <p>El {@code scoreCalidad} es un puntaje compuesto 0-100 que combina cumplimiento
 * de SLA, cantidad de escalas y holgura para reportar calidad de la ruta de un vistazo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditoriaEnvio {
    private String  idEnvio;
    private String  origen;
    private String  destino;
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
    private double  costoTotal;
    private boolean cumpleSLA;
    private boolean sinCiclos;
    private boolean sinDirecto;
    private boolean escalaMinOK;
    private boolean capacidadVuelosOK;
    private boolean almacenDestinoOK;
    private int     scoreCalidad;
}
