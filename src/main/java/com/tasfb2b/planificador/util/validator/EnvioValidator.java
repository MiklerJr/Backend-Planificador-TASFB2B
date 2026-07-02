package com.tasfb2b.planificador.util.validator;


public final class EnvioValidator {

    private EnvioValidator() {
    }

    public static boolean esMismoAeropuerto(String origenIcao, String destinoIcao) {
        return origenIcao != null && origenIcao.equals(destinoIcao);
    }

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
