package com.tasfb2b.planificador.model;

/**
 * Clasificador del envío según los continentes de origen y destino (RF01).
 * <ul>
 *   <li>{@link #INTRACONTINENTAL}: origen y destino en el mismo continente.</li>
 *   <li>{@link #INTERCONTINENTAL}: origen y destino en continentes distintos.</li>
 * </ul>
 */
public enum TipoEnvio {

    INTRACONTINENTAL,
    INTERCONTINENTAL;

    /**
     * Deriva el tipo de envío comparando el continente de origen y destino.
     * Centraliza la regla para no duplicar la comparación en cada loader.
     */
    public static TipoEnvio derivar(Aeropuerto origen, Aeropuerto destino) {
        return origen.getContinente().equals(destino.getContinente())
                ? INTRACONTINENTAL
                : INTERCONTINENTAL;
    }
}
