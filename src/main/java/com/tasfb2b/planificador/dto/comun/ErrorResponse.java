package com.tasfb2b.planificador.dto.comun;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Cuerpo uniforme de error de la API REST. Lo produce {@code GlobalExceptionHandler} para TODOS los
 * fallos manejados (parámetros inválidos → 400, error interno → 500, etc.), reemplazando los
 * {@code Map.of("error", ...)} dispersos que cada endpoint construía a mano.
 *
 * <p><b>Compatibilidad con el front:</b> históricamente los endpoints devolvían {@code {"error":
 * "..."}} con el mensaje legible en la clave {@code error}. Esa clave se CONSERVA con la misma
 * semántica (mensaje legible) para no romper a quien ya la consume. {@code mensaje} es el campo
 * canónico hacia adelante (mismo valor que {@code error}); el front puede migrar a él sin prisa.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /** Mensaje legible del error. Clave conservada por compatibilidad con el front (lee {@code body.error}). */
    private String error;
    /** Alias canónico de {@code error} (mismo valor); el campo a usar de aquí en adelante. */
    private String mensaje;
    /** Código HTTP del error (400, 500, ...). Additive: evita que el front tenga que parsear el status. */
    private int estado;
    /** Instante del error en ISO-8601 (hora del servidor). */
    private String timestamp;
    /** Ruta del request que falló (p. ej. {@code /api/planificador/escenario2/iniciar}); null si no se conoce. */
    private String path;

    /**
     * Construye el cuerpo a partir del código HTTP, el mensaje legible y la ruta del request.
     * {@code error} y {@code mensaje} se rellenan con el mismo texto (ver nota de compatibilidad).
     */
    public static ErrorResponse of(int estado, String mensaje, String path) {
        return new ErrorResponse(mensaje, mensaje, estado, LocalDateTime.now().toString(), path);
    }
}
