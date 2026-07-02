package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.comun.ErrorResponse;
import com.tasfb2b.planificador.exception.GlobalExceptionHandler;
import com.tasfb2b.planificador.exception.ParametroInvalidoException;
import com.tasfb2b.planificador.services.ingesta.IngestaService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato del {@code GlobalExceptionHandler} (manejo centralizado de errores, Tanda 1C):
 * <ul>
 *   <li>una excepción de dominio ({@link ParametroInvalidoException}) produce 400 con el cuerpo
 *       uniforme {@link ErrorResponse}, conservando la clave {@code error} que el front consume;</li>
 *   <li>cualquier excepción no prevista produce 500 con un mensaje genérico (sin filtrar el detalle
 *       interno);</li>
 *   <li>verificación end-to-end con MockMvc: al lanzar el controller la excepción, el advice la
 *       traduce al status y al JSON esperados.</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void excepcionDeDominioProduce400ConClaveError() {
        ResponseEntity<ErrorResponse> resp =
                handler.handleIllegalArgument(new ParametroInvalidoException("k es fijo en el escenario 2: 144 (recibido: 7)"), null);

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
        ResponseEntity<ErrorResponse> resp = handler.handleRutaInexistente(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "api/.env", null), null);

        assertEquals(404, resp.getStatusCode().value());
        ErrorResponse body = resp.getBody();
        assertEquals(404, body.getEstado());
        assertEquals("Recurso no encontrado", body.getError());
    }

    @Test
    void endToEndExcepcionDeDominioSeTraduceA400ConCuerpoUniforme() throws Exception {
        // El controller no necesita servicio para esta ruta: la verificación de k-fijo lanza antes
        // de tocar el servicio, así que basta con props (defaults) + el advice registrado.
        EscenarioController controller = new EscenarioController(null, new PlanificadorProperties(),
                new IngestaService(null, null, null, null, null, null, null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
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
