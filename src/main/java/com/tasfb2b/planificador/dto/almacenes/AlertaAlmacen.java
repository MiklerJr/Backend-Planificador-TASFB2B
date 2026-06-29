package com.tasfb2b.planificador.dto.almacenes;

import lombok.Data;

/**
 * Alerta de almacén cerca de colapso de su capacidad, calculada POR BLOQUE (el peor almacén del
 * bloque por % de ocupación). Viaja embebida en {@link BloqueSimulacion#alertaAlmacen} para que
 * el front la coloque en el momento exacto de la animación, no como valor global "en vivo"
 * (el backend va por delante de lo que el front anima). El {@code nivel} es el mismo semáforo
 * que {@link OcupacionAlmacen} (umbrales 0.70/0.90): "VERDE" | "AMBAR" | "ROJO".
 */
@Data
public class AlertaAlmacen {
    /** Semáforo del peor almacén del bloque: "VERDE" | "AMBAR" | "ROJO". */
    private String nivel;
    /** ICAO del almacén con mayor % de ocupación en el bloque (null si el bloque no tocó ninguno). */
    private String almacenCritico;
    private int capacidadMaxima;
    private int ocupacion;
    private double porcentajeOcupacion;
    /** Índice del bloque al que corresponde (igual que {@link BloqueSimulacion#bloqueIdx}). */
    private int bloqueIdx;
}
