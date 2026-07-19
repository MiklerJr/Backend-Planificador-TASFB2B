package com.tasfb2b.planificador.controlador;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.dto.comun.ErrorResponse;
import com.tasfb2b.planificador.excepcion.ManejadorExcepcionesGlobal;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.servicios.ingesta.IngestaService;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato del {@code ManejadorExcepcionesGlobal} (manejo centralizado de errores, Tanda 1C):
 * <ul>
 *   <li>una excepción de dominio ({@link ParametroInvalidoException}) produce 400 con el cuerpo
 *       uniforme {@link ErrorResponse}, conservando la clave {@code error} que el front consume;</li>
 *   <li>cualquier excepción no prevista produce 500 con un mensaje genérico (sin filtrar el detalle
 *       interno);</li>
 *   <li>verificación end-to-end con MockMvc: al lanzar el controller la excepción, el advice la
 *       traduce al status y al JSON esperados.</li>
 * </ul>
 */
class ManejadorExcepcionesGlobalTest {

    private final ManejadorExcepcionesGlobal handler = new ManejadorExcepcionesGlobal();

    @Test
    void excepcionDeDominioProduce400ConClaveError() {
        ResponseEntity<ErrorResponse> resp =
                handler.manejarArgumentoIlegal(new ParametroInvalidoException("k es fijo en el escenario 2: 144 (recibido: 7)"), null);

        assertEquals(400, resp.getStatusCode().value());
        ErrorResponse body = resp.getBody();
        assertEquals(400, body.getEstado());
        // La clave `error` conserva el mensaje legible (compatibilidad con el front).
        assertTrue(body.getError().contains("k es fijo en el escenario 2"));
        // `mensaje` es el alias canónico: mismo texto que `error`.
        assertEquals(body.getError(), body.getMensaje());
    }

    @Test
    void excepcionGenericaProduce500SinFiltrarDetalle() {
        ResponseEntity<ErrorResponse> resp =
                handler.handleGenerico(new RuntimeException("NPE con detalle interno sensible"), null);

        assertEquals(500, resp.getStatusCode().value());
        ErrorResponse body = resp.getBody();
        assertEquals(500, body.getEstado());
        assertEquals("Error interno del servidor", body.getError());
        // El detalle real de la excepción NO se expone al cliente (se loguea en el servidor).
        assertFalse(body.getError().contains("sensible"));
    }

    @Test
    void rutaInexistenteProduce404SinTratarseComoErrorInterno() {
        // Sondas de bots (/api/.env, /api/v0/run_sql, …) llegaban al handler genérico como 500 con
        // stack trace ERROR; el contrato correcto para una ruta que no existe es 404.
        ResponseEntity<ErrorResponse> resp = handler.manejarRutaInexistente(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "api/.env", null), null);

        assertEquals(404, resp.getStatusCode().value());
        ErrorResponse body = resp.getBody();
        assertEquals(404, body.getEstado());
        assertEquals("Recurso no encontrado", body.getError());
    }

    @Test
    void desconexionDelClienteEnResultadoNoSeLogueaComoError() {
        // Cadena real del stack trace de /resultado cuando el navegador cierra la descarga en curso:
        // HttpMessageNotWritableException → AsyncRequestNotUsableException → ClientAbortException → IOException.
        HttpMessageNotWritableException ex = new HttpMessageNotWritableException(
                "Could not write JSON",
                new AsyncRequestNotUsableException("ServletOutputStream failed to write",
                        new ClientAbortException(new IOException("Connection reset by peer"))));

        ListAppender<ILoggingEvent> appender = adjuntarAppender();
        try {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/planificador/jobs/JOB-1/resultado");

            ResponseEntity<ErrorResponse> resp = handler.manejarEscrituraRespuesta(ex, request);

            // No se reintenta escribir la respuesta ya comprometida: retorno null (no-op para Spring).
            assertNull(resp);
            // El evento benigno NO produce un log de nivel ERROR (a lo sumo un WARN de una línea).
            assertFalse(hayEventoDeNivel(appender, Level.ERROR),
                    "una desconexión del cliente no debe loguearse como ERROR");
        } finally {
            desadjuntarAppender(appender);
        }
    }

    @Test
    void errorRealDeSerializacionSigueSiendo500() {
        // Canario anti over-swallow: un HttpMessageNotWritableException que NO es desconexión
        // (bug de serialización genuino) mantiene el tratamiento de siempre: 500 + ERROR.
        HttpMessageNotWritableException ex = new HttpMessageNotWritableException(
                "No serializer found for class …", new RuntimeException("boom"));

        ListAppender<ILoggingEvent> appender = adjuntarAppender();
        try {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/api/planificador/jobs/JOB-1/resultado");

            ResponseEntity<ErrorResponse> resp = handler.manejarEscrituraRespuesta(ex, request);

            assertEquals(500, resp.getStatusCode().value());
            assertEquals("Error interno del servidor", resp.getBody().getError());
            assertTrue(hayEventoDeNivel(appender, Level.ERROR),
                    "un error de serialización real sí debe loguearse como ERROR");
        } finally {
            desadjuntarAppender(appender);
        }
    }

    // --------------------------------------------------------------------- helpers de logging

    private static ListAppender<ILoggingEvent> adjuntarAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ManejadorExcepcionesGlobal.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void desadjuntarAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ManejadorExcepcionesGlobal.class);
        logger.detachAppender(appender);
    }

    private static boolean hayEventoDeNivel(ListAppender<ILoggingEvent> appender, Level nivel) {
        return appender.list.stream().anyMatch(e -> e.getLevel() == nivel);
    }

    @Test
    void endToEndExcepcionDeDominioSeTraduceA400ConCuerpoUniforme() throws Exception {
        // El controller no necesita servicio para esta ruta: la verificación de k-fijo lanza antes
        // de tocar el servicio, así que basta con props (defaults) + el advice registrado.
        EscenarioController controller = new EscenarioController(null, null, new PlanificadorProperties(),
                new IngestaService(null, null, null, null, null, null, null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ManejadorExcepcionesGlobal())
                .build();

        mvc.perform(post("/api/planificador/escenario2/iniciar").param("k", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("k es fijo en el escenario 2")))
                .andExpect(jsonPath("$.mensaje").exists())
                .andExpect(jsonPath("$.estado").value(400));
    }
}
