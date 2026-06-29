package com.tasfb2b.planificador.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registro de un envío inyectado EN VIVO ya APLICADO por el worker (liberado a la simulación). Se
 * acumula a lo largo de la corrida y se expone EN VIVO al front ({@code enviosInyectados} de
 * {@code GET /jobs/{id}/estado}) para que sepa qué envíos adicionales entraron y en qué bloque.
 *
 * <p>Distinto de la entidad persistida {@code model.solucion.EnvioInyectado} (tabla {@code envio_inyectado})
 * y del request {@code dto.InyeccionEnviosRequest}. {@code idEnvio} es sintético ({@code "INV-..."})
 * y {@code readyTimeUtc} está en <b>UTC</b>.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnvioInyectadoInfo {
    /** Id sintético del envío inyectado ("INV-bloque-n"). */
    private String idEnvio;
    /** Código ICAO de origen. */
    private String origen;
    /** Código ICAO de destino. */
    private String destino;
    /** Número de maletas del lote. */
    private int cantidad;
    /** Identificador del cliente (puede ser null). */
    private Integer clienteId;
    /** SLA en horas (24 intracontinental, 48 intercontinental). */
    private int slaHoras;
    /** readyTime efectivo, en <b>UTC</b>, como string ISO. */
    private String readyTimeUtc;
    /** Índice del bloque en que el envío entró a la simulación. */
    private int bloqueIdx;
    /** E1 operación: empleado registrador que dio de alta el envío (opcional). */
    private String registrador;
    /** E1 operación: sede del registrador (opcional, p. ej. "Lima"). */
    private String sede;
}
