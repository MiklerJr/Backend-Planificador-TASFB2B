package com.tasfb2b.planificador.dto;

import lombok.Data;

import java.util.List;

/**
 * Read model de ocupación de almacenes por bloque (Tanda 1B), expuesto por
 * {@code GET /jobs/{id}/almacenes/ocupacion}. Cada fila es el pico concurrente ACUMULADO del
 * almacén-día al cierre de su bloque (ver {@link OcupacionAlmacenRow}); el front NO debe sumar filas
 * entre bloques.
 */
@Data
public class OcupacionAlmacenesResponse {
    private String jobId;
    private int total;
    private List<OcupacionAlmacenRow> almacenes;
}
