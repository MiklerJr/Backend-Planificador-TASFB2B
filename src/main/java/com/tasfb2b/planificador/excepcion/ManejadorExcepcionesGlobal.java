package com.tasfb2b.planificador.excepcion;

import com.tasfb2b.planificador.dto.comun.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

@RestControllerAdvice
public class ManejadorExcepcionesGlobal {

    private static final Logger log = LoggerFactory.getLogger(ManejadorExcepcionesGlobal.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> manejarArgumentoIlegal(IllegalArgumentException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> manejarParametroInvalido(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Parámetro inválido: " + ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> manejarCuerpoNoLegible(HttpMessageNotReadableException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Cuerpo de la petición ausente o malformado", request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> manejarRutaInexistente(Exception ex, HttpServletRequest request) {
        log.warn("Ruta inexistente: {}", pathDe(request));
        return build(HttpStatus.NOT_FOUND, "Recurso no encontrado", request);
    }

    /**
     * Fallos al escribir la respuesta. El caso frecuente en {@code /jobs/{id}/resultado} es una
     * <em>desconexión del cliente</em> a mitad de la descarga (navegación, F5, cierre de pestaña,
     * corte de red): el navegador cierra el socket y Tomcat lanza {@link ClientAbortException} —
     * envuelto en {@link AsyncRequestNotUsableException}/{@link HttpMessageNotWritableException}—
     * al seguir escribiendo. Es un evento benigno, no un bug: la respuesta ya está comprometida y
     * reintentar escribir vuelve a fallar. Se loguea como {@code WARN} de una línea (sin stacktrace)
     * y se retorna {@code null} para que Spring no reintente escribir en el socket cerrado.
     *
     * <p>Un {@link HttpMessageNotWritableException} que NO sea desconexión (error real de
     * serialización) mantiene el tratamiento de siempre: {@code 500} con stacktrace en {@code ERROR}.
     */
    @ExceptionHandler({HttpMessageNotWritableException.class,
                       ClientAbortException.class,
                       AsyncRequestNotUsableException.class})
    public ResponseEntity<ErrorResponse> manejarEscrituraRespuesta(Exception ex,
                                                                    HttpServletRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            log.warn("Cliente cerró la conexión durante {}", pathDe(request));
            return null; // no-op: HttpEntityMethodProcessor no reescribe si el retorno es null
        }
        log.error("Error no controlado en {}", pathDe(request), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor", request);
    }

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
