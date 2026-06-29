package com.tasfb2b.planificador.dto.almacenes;

import lombok.Data;

import java.util.List;

/**
 * Read model de ocupación de almacenes por bloque (Tanda 1B), expuesto por
 * {@code GET /jobs/{id}/almacenes/ocupacion}. Cada fila es el pico concurrente ACUMULADO del
 * almacén-día al cierre de su bloque (ver {@link OcupacionAlmacenRow}); el front NO debe sumar filas
 * entre bloques.
 *
 * <p>Anti-OOM: la respuesta es PAGINADA (mismo contrato que {@link CargaVuelosResponse}). {@code total}
 * = filas EN ESTA PÁGINA. El front itera desde {@code desde=0} y, mientras {@code hayMas=true}, vuelve
 * a pedir con {@code desde=proximoDesde}. El cursor es OPACO; el front solo lo reutiliza.
 */
@Data
public class OcupacionAlmacenesResponse {
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
    private List<OcupacionAlmacenRow> almacenes;
}
