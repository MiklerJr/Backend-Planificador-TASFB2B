package com.tasfb2b.planificador.dto.vuelos;

import lombok.Data;

/**
 * Fila tipada de carga de vuelo-día contextualizada por bloque. Es un {@link CargaVuelo}
 * (carga ACUMULADA del vuelo-día) más las coordenadas del bloque que lo reportó
 * ({@code bloqueIdx}, {@code horaInicio}, {@code horaFin}). La usan los read models
 * {@code GET /jobs/{id}/vuelos/carga} y la sección {@code vuelos} de {@code /indicadores}.
 *
 * <p>El JSON expone los mismos nombres, tipos y orden de campo del contrato del front.
 */
@Data
public class CargaVueloRow {
    private String vueloId;
    private String origen;
    private String destino;
    private String fechaSalida;
    private String fechaLlegada;
    private int capacidadMaxima;
    private int cargaAsignada;
    private double porcentajeCarga;
    private String semaforo;
    private int bloqueIdx;
    private String horaInicio;
    private String horaFin;
}
