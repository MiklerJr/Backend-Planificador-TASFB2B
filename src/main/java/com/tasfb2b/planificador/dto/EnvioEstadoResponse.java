package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Estado de un envío "en ruta" en un instante dado, para la consulta puntual del front
 * ({@code GET /jobs/{jobId}/envios/{idEnvio}}). Envuelve la {@link AsignacionMaleta} reconstruida
 * (con cada {@link TramoRuta#getEstado()} ya clasificado) y añade el estado global del envío y su
 * ubicación, derivados comparando los tiempos UTC de los tramos contra el "ahora".
 *
 * <p>El "ahora" lo fija el parámetro {@code en} de la petición; si se omite, el backend usa el
 * {@code horaFin} del último bloque publicado del job ({@code instanteDerivadoDelJob = true}).
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnvioEstadoResponse {

    /** Detalle completo del envío y su ruta; sus {@code tramos[].estado} vienen ya rellenos. */
    private AsignacionMaleta asignacion;

    /**
     * Estado global del envío respecto al instante de referencia:
     * {@code PROGRAMADO} (aún no sale el 1.er tramo), {@code EN_VUELO} (en un tramo en curso),
     * {@code EN_ESCALA} (esperando conexión entre tramos), {@code ENTREGADO} (tras el último tramo)
     * o {@code DESCONOCIDO} (no se pudo determinar el "ahora").
     */
    private String estado;

    /** Instante UTC usado como referencia (ISO sin offset), o null si no se pudo determinar. */
    private String instanteReferencia;

    /** {@code true} si el instante salió del último bloque del job (no del parámetro {@code en}). */
    private boolean instanteDerivadoDelJob;

    /**
     * ICAO del aeropuerto donde está físicamente el envío: origen si {@code PROGRAMADO}, la escala
     * si {@code EN_ESCALA}, el destino final si {@code ENTREGADO}. Null si {@code EN_VUELO} (en el
     * aire: usar {@code tramoActualIdx}) o {@code DESCONOCIDO}.
     */
    private String ubicacionActual;

    /** Índice (0-based) del tramo en curso si {@code EN_VUELO}; null en otro caso. */
    private Integer tramoActualIdx;

    private int tramosCompletados;
    private int tramosTotales;

    /** {@code llegadaUtc} del último tramo (llegada al destino final). Null si la ruta no tiene tramos. */
    private String llegadaFinalUtc;
}
