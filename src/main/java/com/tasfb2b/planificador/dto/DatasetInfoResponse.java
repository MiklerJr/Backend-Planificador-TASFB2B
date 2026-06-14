package com.tasfb2b.planificador.dto;

import lombok.Data;

/**
 * Metadatos del dataset cargado (Tanda 1B): rango temporal disponible y conteos. Lo devuelve
 * {@code GET /dataset/info} para que el front valide {@code fechaInicio} antes de lanzar un job.
 *
 * <p>{@code primeraVentana}/{@code ultimaVentana} se emiten SIEMPRE, incluso {@code null} cuando el
 * dataset está vacío (mismo comportamiento que el mapa anterior). {@code totalMaletas} se mantiene
 * por compatibilidad: históricamente equivale al número de envíos/filas.
 */
@Data
public class DatasetInfoResponse {
    private String primeraVentana;
    private String ultimaVentana;
    private long diasDisponibles;
    private int totalMaletas;
    private int totalEnvios;
    private long totalMaletasIndividuales;
    private int totalAeropuertos;
    private int totalVuelos;
}
