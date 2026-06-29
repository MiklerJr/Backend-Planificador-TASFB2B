package com.tasfb2b.planificador.dto.vuelos;

import lombok.Data;

import java.util.List;

/**
 * Read model de carga de vuelos por bloque (Tanda 1B), expuesto por
 * {@code GET /jobs/{id}/vuelos/carga}. Cada fila es la carga ACUMULADA del vuelo-día al cierre de su
 * bloque (ver {@link CargaVueloRow}); el front NO debe sumar filas entre bloques.
 *
 * <p>Anti-OOM: la respuesta es PAGINADA. {@code total} = filas EN ESTA PÁGINA (no el global). El
 * front itera empezando en {@code desde=0} y, mientras {@code hayMas=true}, vuelve a pedir con
 * {@code desde=proximoDesde}. El cursor {@code desde}/{@code proximoDesde} es OPACO (en la ventana
 * RAM cuenta bloques; en el histórico BD cuenta filas) — el front solo lo reutiliza, no lo interpreta.
 */
@Data
public class CargaVuelosResponse {
    private String jobId;
    /** Cursor de reanudación usado en esta página (eco del parámetro {@code desde}). */
    private int desde;
    /** Cursor a pasar como {@code desde} en la siguiente página. */
    private int proximoDesde;
    /** {@code true} si quedan más filas más allá de esta página. */
    private boolean hayMas;
    /** Nº de bloques publicados por el job hasta ahora (contexto para el front). */
    private int bloquesPublicados;
    /** {@code true} si el job ya alcanzó un estado terminal. */
    private boolean terminado;
    /** Filas EN ESTA PÁGINA (no el total global). */
    private int total;
    private List<CargaVueloRow> vuelos;
}
