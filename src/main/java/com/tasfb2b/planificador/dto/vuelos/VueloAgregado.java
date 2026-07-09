package com.tasfb2b.planificador.dto.vuelos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado de un alta de vuelo EN CALIENTE ({@link AltaVueloRequest}) tal como se expone en
 * {@code /estado}: en {@code vuelosAgregados} si se aplicó (motivo null) o en
 * {@code altasVueloNoAplicadas} con el motivo del rechazo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VueloAgregado {
    /** Id normalizado "ORIGEN-DESTINO-HHMM" (null si el request era tan inválido que no se pudo derivar). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String idVuelo;
    private String origen;
    private String destino;
    /** Hora de salida LOCAL del origen, "HH:mm". */
    private String horaSalida;
    /** Hora de llegada LOCAL del destino, "HH:mm". */
    private String horaLlegada;
    private int capacidad;
    /** Bloque en cuya frontera se aplicó (o se rechazó) el alta. */
    private int bloqueIdx;
    /** null = aplicada; texto = por qué no se aplicó. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String motivo;
}
