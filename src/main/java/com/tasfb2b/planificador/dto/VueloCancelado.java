package com.tasfb2b.planificador.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Registro de un vuelo cancelado por orden del usuario durante una corrida. Se acumula a lo largo de
 * la simulación y se vuelca como un CSV ({@code <jobId>-vuelos-cancelados.csv}) dentro del ZIP de
 * auditoría para dejar trazabilidad de qué se canceló y cuántos envíos se vieron afectados.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VueloCancelado {
    /** Código ICAO del aeropuerto de origen del vuelo cancelado. */
    private String origen;
    /** Código ICAO del aeropuerto de destino del vuelo cancelado. */
    private String destino;
    /** Fecha y hora de salida del vuelo-día cancelado. */
    private LocalDateTime fechaHoraSalida;
    /** Envíos ya comprometidos en ese vuelo-día que se devolvieron al backlog para re-enrutar. */
    private int enviosAfectados;
}
