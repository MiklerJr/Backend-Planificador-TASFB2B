package com.tasfb2b.planificador.util.validator;

import com.tasfb2b.planificador.model.dataset.Vuelo;


public final class VueloValidator {

    private VueloValidator() {}

    public static boolean esCoherente(Vuelo v) {
        return v != null
                && v.getCapacidad() != null && v.getCapacidad() > 0
                && v.getOrigen() != null && v.getDestino() != null
                && !v.getOrigen().equals(v.getDestino());
    }
}
