package com.tasfb2b.planificador.dto.datos;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alta de un aeropuerto enviada por el usuario desde el front EN VIVO durante una simulación.
 * El aeropuerto es <b>efímero</b>: existe solo para esa corrida (se revierte al iniciar la
 * corrida siguiente, sin contaminar el dataset maestro). Por sí solo no cambia ninguna ruta:
 * participa cuando se agregan vuelos EN CALIENTE hacia/desde él o se inyectan envíos.
 *
 * <p>El {@code continente} (AM/EU/AS) determina el SLA de los envíos (24 h intracontinental /
 * 48 h intercontinental). Si se omite, se deriva del prefijo ICAO (S→AM, E/L/U→EU, O/V→AS);
 * con prefijo desconocido el alta se rechaza con 400 (exige {@code continente} explícito).
 */
@Data
@NoArgsConstructor
public class AltaAeropuertoRequest {
    /** Código ICAO nuevo (4 letras mayúsculas; no debe existir). */
    private String icao;
    /** Nombre de la ciudad (opcional, informativo para el front). */
    private String ciudad;
    /** GMT offset del aeropuerto, en horas [-12..14]. Obligatorio (normalización UTC). */
    private Integer husoHorario;
    /** Capacidad de almacén (maletas concurrentes), entero >= 1. */
    private int capacidad;
    /** Latitud (opcional, para el mapa del front). */
    private Double latitud;
    /** Longitud (opcional, para el mapa del front). */
    private Double longitud;
    /** Continente AM/EU/AS (opcional si el prefijo ICAO lo deriva; ver Javadoc de la clase). */
    private String continente;
}
