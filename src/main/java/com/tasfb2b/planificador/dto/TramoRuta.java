package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
public class TramoRuta {
    private String vueloId;
    private String origen;
    private String destino;
    /** UTC real (offset del aeropuerto de origen aplicado). Despegue del tramo
     *  en el eje de tiempo global; usarlo para animar la posición del avión. */
    private String salidaUtc;
    /** UTC real (offset del aeropuerto de destino aplicado). Aterrizaje del tramo
     *  en el eje de tiempo global. */
    private String llegadaUtc;
    /** ISO datetime sin offset (hora de pared local del origen). Despegue del tramo. */
    private String salidaLocal;
    /** ISO datetime sin offset (hora de pared local del destino). Aterrizaje del tramo. */
    private String llegadaLocal;
    /**
     * Duración real del vuelo en minutos (UTC), ya con los husos aplicados.
     * Es {@code llegadaUtc − salidaUtc}. USAR ESTE valor para velocidad/animación;
     * NO restar los campos {@code *Local}, que están en husos distintos y dan
     * duraciones falsas (negativas o infladas).
     */
    private int duracionMin;
    /**
     * Estado del tramo respecto al instante actual de la simulación: {@code COMPLETADO}
     * ({@code llegadaUtc <= ahora}), {@code EN_CURSO} ({@code salidaUtc <= ahora < llegadaUtc})
     * o {@code PENDIENTE} ({@code salidaUtc > ahora}). Solo lo rellena el endpoint de estado del
     * envío ({@code GET /jobs/{id}/envios/{idEnvio}}); en {@code /bloques} y {@code /asignaciones}
     * queda null y no aparece en el JSON.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String estado;
}
