package com.tasfb2b.planificador.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Estimación —SIN generar el ZIP— de cuántos archivos CSV tendría la auditoría de un job en un rango,
 * para que el front avise al usuario del tamaño antes de descargar. Calculada con conteos en BD:
 * <ul>
 *   <li>{@code filasEnvios}: envíos a exportar (enrutados en BD + sin-ruta en RAM) en el rango.</li>
 *   <li>{@code csvEnvios}: archivos de envíos = {@code ceil(filasEnvios / filasPorArchivo)}.</li>
 *   <li>{@code filasCancelaciones}: vuelo-días cancelados en el rango (por día).</li>
 *   <li>{@code csvCancelaciones}: 1 (el CSV de cancelaciones siempre se emite, aun vacío).</li>
 *   <li>{@code totalCsv}: {@code csvEnvios + csvCancelaciones}.</li>
 * </ul>
 * {@code desdeEfectivo}/{@code hastaEfectivo} es el rango UTC realmente contado (recortado a la ventana
 * simulada); {@code recortado} indica si se ajustó un límite explícito del cliente.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimacionAuditoria {
    private long filasEnvios;
    private int  csvEnvios;
    private long filasCancelaciones;
    private int  csvCancelaciones;
    private int  totalCsv;
    private int  filasPorArchivo;
    private String desdeEfectivo;
    private String hastaEfectivo;
    private boolean recortado;
}
