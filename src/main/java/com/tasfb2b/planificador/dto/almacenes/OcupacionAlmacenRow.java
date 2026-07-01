package com.tasfb2b.planificador.dto.almacenes;

import lombok.Data;

/**
 * Fila tipada de ocupación de almacén-día contextualizada por bloque. Es un
 * {@link OcupacionAlmacen} (pico concurrente ACUMULADO del día) más las coordenadas del bloque que
 * lo reportó ({@code bloqueIdx}, {@code horaInicio}, {@code horaFin}). La usan los read models
 * {@code GET /jobs/{id}/almacenes/ocupacion} y la sección {@code almacenes} de {@code /indicadores}.
 *
 * <p>El JSON expone los mismos nombres, tipos y orden de campo del contrato del front.
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
