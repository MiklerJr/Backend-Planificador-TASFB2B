package com.tasfb2b.planificador.exception;

/**
 * Excepción de dominio para parámetros de request inválidos (k fijo violado, sa/ta ≤ 0, fechaInicio
 * fuera del dataset, escenario no reiniciable, algoritmo no soportado, ...). La captura
 * {@code GlobalExceptionHandler} y la traduce a {@code 400 Bad Request} con el cuerpo uniforme
 * {@code ErrorResponse} (conservando la clave {@code error} que el front ya consume).
 *
 * <p>Extiende {@link IllegalArgumentException} a propósito: así el mismo handler cubre tanto estas
 * validaciones explícitas como los {@code IllegalArgumentException} que ya lanzan capas internas
 * (p. ej. {@code resolverMotor} ante un motor desconocido), que antes caían a un 500 sin manejar.
 */
public class ParametroInvalidoException extends IllegalArgumentException {

    public ParametroInvalidoException(String mensaje) {
        super(mensaje);
    }
}
