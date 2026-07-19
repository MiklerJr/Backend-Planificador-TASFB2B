package com.tasfb2b.planificador.algoritmo.alns;

/**
 * Señal de pre-colapso que {@link OperadorReparacionVoraz#evaluarPreColapso} devuelve por bloque:
 * utilización máxima de almacén (y su aeropuerto crítico) + holgura SLA mínima del envío más
 * urgente del backlog. Alimenta la {@code AlertaColapso} (VERDE/AMBAR/ROJO) de la telemetría.
 */
public record PreColapso(double utilAlmacenMax, String almacenCritico,
                         double holguraSlaMin, String envioUrgente) {}
