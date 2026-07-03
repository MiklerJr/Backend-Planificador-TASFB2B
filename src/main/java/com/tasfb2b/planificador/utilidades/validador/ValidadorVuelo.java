package com.tasfb2b.planificador.utilidades.validador;

import com.tasfb2b.planificador.modelo.datos.Vuelo;


public final class ValidadorVuelo {

    private ValidadorVuelo() {}

    public static boolean esCoherente(Vuelo v) {
        return v != null
                && v.getCapacidad() != null && v.getCapacidad() > 0
                && v.getOrigen() != null && v.getDestino() != null
                && !v.getOrigen().equals(v.getDestino());
    }
}
