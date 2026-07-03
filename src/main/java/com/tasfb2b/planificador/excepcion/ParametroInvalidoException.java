package com.tasfb2b.planificador.excepcion;

public class ParametroInvalidoException extends IllegalArgumentException {

    public ParametroInvalidoException(String mensaje) {
        super(mensaje);
    }
}
