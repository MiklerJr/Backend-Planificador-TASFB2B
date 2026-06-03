package com.tasfb2b.planificador.util;

/**
 * Validaciones de negocio para los envíos (RF02).
 */
public final class EnvioValidator {

    private EnvioValidator() {
    }

    /**
     * Indica si el aeropuerto de origen y el de destino son el mismo (RF02).
     * La comparación se hace por código ICAO, identificador natural del aeropuerto.
     *
     * @return {@code true} si ambos códigos no son nulos y son iguales.
     */
    public static boolean esMismoAeropuerto(String origenIcao, String destinoIcao) {
        return origenIcao != null && origenIcao.equals(destinoIcao);
    }
}
