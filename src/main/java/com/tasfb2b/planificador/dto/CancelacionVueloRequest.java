package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Orden de cancelación de un vuelo concreto enviada por el usuario desde el front EN VIVO durante
 * una simulación. El vuelo queda no disponible <b>solo ese día</b> (el de {@code fechaHoraSalida});
 * los envíos ya programados en él se devuelven al backlog y se re-enrutan en los bloques siguientes.
 *
 * <p>El vuelo se identifica por su trayecto y su hora/fecha de salida —los mismos datos que el
 * front recibe en {@code VuelosUsadosResponse.VueloUsado} ({@code origen}, {@code destino},
 * {@code fechaSalida})—, que el backend mapea al vuelo-día interno.
 *
 * <p><b>Eje UTC:</b> {@code fechaHoraSalida} debe ir en <b>UTC</b>, el mismo eje que
 * {@code VueloUsado.fechaSalida} (= {@code TramoRuta.salidaUtc}). El front <b>reenvía ese valor tal
 * cual</b>, sin convertir a hora local: el backend lo compara contra {@code Edge.depMinuteOfDay},
 * que está normalizado a UTC, y deriva el día del mismo valor. Enviar la hora local de pared
 * cancelaría el vuelo equivocado o no encontraría ninguno (salvo en aeropuertos con offset 0).
 */
@Data
@NoArgsConstructor
public class CancelacionVueloRequest {
    /** Código ICAO del aeropuerto de origen del vuelo. */
    private String origen;
    /** Código ICAO del aeropuerto de destino del vuelo. */
    private String destino;
    /**
     * Fecha y hora de salida del vuelo a cancelar, en <b>UTC</b> (el {@code fechaSalida} que
     * devuelve {@code GET /vuelos/usados}, reenviado tal cual). La hora identifica el vuelo dentro
     * del trayecto (origen→destino) y la fecha fija el día concreto que se cancela.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime fechaHoraSalida;
}
