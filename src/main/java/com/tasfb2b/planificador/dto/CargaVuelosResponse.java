package com.tasfb2b.planificador.dto;

import lombok.Data;

import java.util.List;

/**
 * Read model de carga de vuelos por bloque (Tanda 1B), expuesto por
 * {@code GET /jobs/{id}/vuelos/carga}. Cada fila es la carga ACUMULADA del vuelo-día al cierre de su
 * bloque (ver {@link CargaVueloRow}); el front NO debe sumar filas entre bloques.
 */
@Data
public class CargaVuelosResponse {
    private String jobId;
    private int total;
    private List<CargaVueloRow> vuelos;
}
