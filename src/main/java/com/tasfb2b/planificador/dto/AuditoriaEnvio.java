package com.tasfb2b.planificador.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Registro de auditoría por envío para validación formal de restricciones del cliente.
 *
 * <p>Cada fila corresponde a un {@code LuggageBatch} procesado por el planificador.
 * Las columnas booleanas (cumpleSLA, sinCiclos, escalaMinOK) permiten verificar de
 * forma independiente que el algoritmo respeta cada restricción del problema TASF.B2B.
 *
 * <p>El {@code scoreCalidad} es un puntaje compuesto 0-100 que combina cumplimiento
 * de SLA, cantidad de escalas y holgura para reportar calidad de la ruta de un vistazo.
 *
 * <p>Los timestamps {@code fechaHoraInicio} y {@code fechaHoraFin} acotan el ciclo
 * de vida del envío extremo a extremo: desde el momento en que el batch queda listo
 * para ser despachado hasta que la maleta queda disponible en el almacén destino
 * (llegada + tiempo de procesamiento en destino, {@code DEST_STORAGE_MIN = 10 min}).
 *
 * <p><b>Eje UTC:</b> {@code registroHHMM}, {@code fechaHoraInicio} y {@code fechaHoraFin} están en
 * <b>UTC</b> (el {@code readyTime} del batch ya viene normalizado a UTC, y la llegada también). Por
 * eso un envío registrado el 2026-01-02 en hora local de un aeropuerto GMT+ aparece aquí con fecha
 * del día anterior. No confundir con la hora local de pared del dataset (p. ej. {@code primeraVentana}
 * de {@code /dataset/info}, que sí es local).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditoriaEnvio {
    private String  idEnvio;
    private String  origen;
    private String  destino;
    /** Identificador del cliente del envío. {@code null} si no está disponible. */
    private Integer clienteId;
    /** Número de maletas físicas del lote (el envío es un lote, no una maleta). */
    private int     cantidad;
    /** Tipo de envío (INTRACONTINENTAL / INTERCONTINENTAL); explica el SLA (24/48 h). */
    private String  tipoEnvio;
    /** HH:MM del registro del envío, en UTC (HH:MM de {@code fechaHoraInicio}). */
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
    /** Holgura de SLA en horas ({@code slackSlaMin / 60}), más legible que en minutos. */
    private double  slackSlaHoras;
    private boolean cumpleSLA;
    private boolean sinCiclos;
    private boolean escalaMinOK;
    private int     scoreCalidad;
    /** Momento de registro del envío (readyTime del batch), en UTC. */
    private LocalDateTime fechaHoraInicio;
    /** Momento en que la maleta queda disponible en el almacén destino, en UTC
     *  (llegada del último vuelo + 10 min de procesamiento). null si no enrutada. */
    private LocalDateTime fechaHoraFin;
}
