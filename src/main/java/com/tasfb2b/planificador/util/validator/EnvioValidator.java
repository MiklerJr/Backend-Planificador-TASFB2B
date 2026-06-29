package com.tasfb2b.planificador.util.validator;

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

    /**
     * Indica si todos los campos obligatorios de un envío están presentes (RF03):
     * ninguno es nulo ni está en blanco. El {@code id} del envío NO se valida con
     * este método porque es opcional (puede venir en blanco, p. ej. en auditorías).
     *
     * @return {@code true} si todos los campos recibidos están presentes.
     */
    public static boolean camposObligatoriosPresentes(String... campos) {
        if (campos == null) {
            return false;
        }
        for (String c : campos) {
            if (c == null || c.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
