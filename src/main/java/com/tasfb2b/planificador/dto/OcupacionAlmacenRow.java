package com.tasfb2b.planificador.dto;

import lombok.Data;

/**
 * Fila tipada de ocupación de almacén-día contextualizada por bloque (Tanda 1B). Es un
 * {@link OcupacionAlmacen} (pico concurrente ACUMULADO del día) más las coordenadas del bloque que
 * lo reportó ({@code bloqueIdx}, {@code horaInicio}, {@code horaFin}). La usan los read models
 * {@code GET /jobs/{id}/almacenes/ocupacion} y la sección {@code almacenes} de {@code /indicadores}.
 *
 * <p>El JSON serializado es idéntico al mapa que se construía antes a mano: mismos nombres y tipos
 * de campo, en el mismo orden.
 */
@Data
public class OcupacionAlmacenRow {
    private String aeropuerto;
    private String fecha;
    private int capacidadMaxima;
    private int ocupacionAsignada;
    private double porcentajeOcupacion;
    private String semaforo;
    private int bloqueIdx;
    private String horaInicio;
    private String horaFin;
}
