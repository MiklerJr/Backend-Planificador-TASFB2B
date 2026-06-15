package com.tasfb2b.planificador.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Registro de un vuelo cancelado por orden del usuario durante una corrida. Se acumula a lo largo de
 * la simulación y se expone EN VIVO al front ({@code vuelosCancelados} de
 * {@code GET /jobs/{id}/estado} y de {@code GET /escenario1/estado}) para que retire del mapa los
 * vuelo-días cancelados; al final se vuelca como CSV ({@code <jobId>-vuelos-cancelados.csv}) dentro
 * del ZIP de auditoría. {@code fechaHoraSalida} está en <b>UTC</b> (el mismo valor del request de
 * cancelación, que es el {@code fechaSalida} UTC de {@code GET /vuelos/usados}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VueloCancelado {
    /** Código ICAO del aeropuerto de origen del vuelo cancelado. */
    private String origen;
    /** Código ICAO del aeropuerto de destino del vuelo cancelado. */
    private String destino;
    /** Fecha y hora de salida del vuelo-día cancelado, en <b>UTC</b>. */
    private LocalDateTime fechaHoraSalida;
    /** Envíos ya comprometidos en ese vuelo-día que se devolvieron al backlog para re-enrutar. */
    private int enviosAfectados;
}
