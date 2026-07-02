package com.tasfb2b.planificador.exception;

import com.tasfb2b.planificador.dto.comun.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Manejo CENTRALIZADO de errores de toda la API REST: produce un cuerpo uniforme
 * ({@link ErrorResponse}) y códigos HTTP consistentes para los errores que escapan de los
 * endpoints.
 *
 * <p>Mapeo de excepciones → estado:
 * <ul>
 *   <li>{@link IllegalArgumentException} (incluye {@code ParametroInvalidoException}) → <b>400</b>.</li>
 *   <li>{@link MethodArgumentTypeMismatchException} / {@link MissingServletRequestParameterException}
 *       (p. ej. {@code fechaInicio} con formato inválido, {@code k} no numérico) → <b>400</b>.</li>
 *   <li>{@link HttpMessageNotReadableException} (body JSON ausente o malformado) → <b>400</b>.</li>
 *   <li>{@link NoResourceFoundException} / {@link NoHandlerFoundException} (ruta que no existe,
 *       típicamente sondas de bots contra el servidor expuesto) → <b>404</b> con log de UNA línea,
 *       sin stack trace.</li>
 *   <li>Cualquier otra {@link Exception} no prevista → <b>500</b> (se loguea con stack trace).</li>
 * </ul>
 *
 * <p><b>Qué NO toca este advice:</b> los 404 de dominio (job/envío inexistente) y 409 (job ya
 * terminado) se resuelven en el controller con {@code ResponseEntity.notFound()}/{@code status(409)}
 * y sus cuerpos propios; no se canalizan por aquí para no alterar el contrato (ver
 * CONTRATO_API_FRONTEND.md §1). El handler de 404 de este advice cubre solo RUTAS inexistentes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Parámetros inválidos lanzados como excepción de dominio o {@code IllegalArgumentException}
     * interno. Antes cada endpoint devolvía {@code badRequest().body(Map.of("error", ...))} a mano;
     * ahora se centraliza aquí conservando la misma clave {@code error} y el mismo status 400.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * Conversión fallida de un parámetro de request (tipo o formato): {@code fechaInicio} mal
     * formada, un entero no parseable, etc. Spring lo trataría como 400 con su cuerpo por defecto;
     * aquí se unifica al mismo {@link ErrorResponse}.
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleParametroInvalido(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Parámetro inválido: " + ex.getMessage(), request);
    }

    /** Body de la petición ausente o JSON malformado (p. ej. {@code POST .../cancelar-vuelo} sin cuerpo válido). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBodyNoLegible(HttpMessageNotReadableException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Cuerpo de la petición ausente o malformado", request);
    }

    /**
     * Ruta que no corresponde a ningún endpoint ni recurso estático (en producción son casi siempre
     * escáneres de bots: {@code /api/.env}, {@code /api/v0/run_sql}, …). Sin este handler caían en
     * el genérico como 500 con ~80 líneas de stack trace en ERROR por sonda; el 404 es el contrato
     * correcto y una línea de WARN basta para conservar la traza de que el servidor está expuesto.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleRutaInexistente(Exception ex, HttpServletRequest request) {
        log.warn("Ruta inexistente: {}", pathDe(request));
        return build(HttpStatus.NOT_FOUND, "Recurso no encontrado", request);
    }

    /**
     * Red de seguridad para cualquier excepción no contemplada: 500 con cuerpo uniforme. Se loguea
     * el fallo completo (con stack trace) pero NO se expone su detalle al cliente, para no filtrar
     * internals.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenerico(Exception ex, HttpServletRequest request) {
        log.error("Error no controlado en {}", pathDe(request), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor", request);
    }

    // ----------------------------------------------------------------------- helpers

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String mensaje,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), mensaje, pathDe(request)));
    }

    private static String pathDe(HttpServletRequest request) {
        return request != null ? request.getRequestURI() : null;
    }
}
