package com.tasfb2b.planificador.dto;

import lombok.Data;

/**
 * Metadatos del dataset cargado (Tanda 1B): rango temporal disponible y conteos. Lo devuelve
 * {@code GET /dataset/info} para que el front valide {@code fechaInicio} antes de lanzar un job.
 *
 * <p>{@code primeraVentana}/{@code ultimaVentana} se emiten SIEMPRE, incluso {@code null} cuando el
 * dataset está vacío (mismo comportamiento que el mapa anterior). {@code totalMaletas} se mantiene
 * por compatibilidad: históricamente equivale al número de envíos/filas.
 *
 * <p><b>Eje de {@code primeraVentana}/{@code ultimaVentana}: hora LOCAL del dataset</b>, no UTC.
 * Son el {@code MIN}/{@code MAX} de {@code fecha_hora_registro} (que la BD guarda en la hora local
 * de cada aeropuerto, mezclando husos). Sirven como referencia para validar {@code fechaInicio}
 * antes de lanzar un job; <b>no son un reloj de animación</b>. Para el eje global usar los
 * {@code *Utc} de las asignaciones/tramos (ver §9 del contrato).
 */
@Data
public class DatasetInfoResponse {
    /** Registro más temprano del dataset, en hora LOCAL del aeropuerto (no UTC). */
    private String primeraVentana;
    /** Registro más tardío del dataset, en hora LOCAL del aeropuerto (no UTC). */
    private String ultimaVentana;
    private long diasDisponibles;
    private int totalMaletas;
    private int totalEnvios;
    private long totalMaletasIndividuales;
    private int totalAeropuertos;
    private int totalVuelos;
}
