package com.tasfb2b.planificador.dto.datos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado de un alta de aeropuerto EN CALIENTE ({@link AltaAeropuertoRequest}) tal como se
 * expone en {@code /estado}: en {@code aeropuertosAgregados} si se aplicó (motivo null) o en
 * {@code altasAeropuertoNoAplicadas} con el motivo del rechazo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AeropuertoAgregado {
    private String icao;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String ciudad;
    private Integer husoHorario;
    private int capacidad;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String continente;
    /** Bloque en cuya frontera se aplicó (o se rechazó) el alta. */
    private int bloqueIdx;
    /** null = aplicada; texto = por qué no se aplicó. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String motivo;
}
