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
 */
@Data
@NoArgsConstructor
public class CancelacionVueloRequest {
    /** Código ICAO del aeropuerto de origen del vuelo. */
    private String origen;
    /** Código ICAO del aeropuerto de destino del vuelo. */
    private String destino;
    /**
     * Fecha y hora de salida del vuelo a cancelar. La hora identifica el vuelo dentro del trayecto
     * (origen→destino) y la fecha fija el día concreto que se cancela.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime fechaHoraSalida;
}
