package com.tasfb2b.planificador.dto.vuelos;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alta de un vuelo enviada por el usuario desde el front EN VIVO durante una simulación.
 * El vuelo es <b>efímero</b>: existe solo para esa corrida (se revierte al iniciar la corrida
 * siguiente, sin contaminar el dataset maestro) y es <b>recurrente diario</b>, como todos los
 * vuelos del dataset (se repite cada día de la simulación).
 *
 * <p><b>Horas LOCALES:</b> {@code horaSalida} es hora local del aeropuerto de origen y
 * {@code horaLlegada} hora local del destino, en formato "HH:mm" — el mismo formato del dataset.
 * El backend normaliza a UTC al construir la arista (igual que con los vuelos del dataset).
 *
 * <p>Se encola y se aplica al inicio del siguiente bloque. El id resultante es
 * {@code ORIGEN-DESTINO-HHMM} (p. ej. "SKBO-SEQM-0830"); el resultado se expone en
 * {@code /estado} ({@code vuelosAgregados} / {@code altasVueloNoAplicadas}).
 */
@Data
@NoArgsConstructor
public class AltaVueloRequest {
    /** Código ICAO del aeropuerto de origen (debe existir). */
    private String origen;
    /** Código ICAO del aeropuerto de destino (debe existir). */
    private String destino;
    /** Hora de salida LOCAL del origen, "HH:mm". */
    private String horaSalida;
    /** Hora de llegada LOCAL del destino, "HH:mm". */
    private String horaLlegada;
    /** Capacidad del vuelo (maletas), entero >= 1. */
    private int capacidad;
}
