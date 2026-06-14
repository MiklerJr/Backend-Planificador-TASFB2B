package com.tasfb2b.planificador.dto;

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
}
