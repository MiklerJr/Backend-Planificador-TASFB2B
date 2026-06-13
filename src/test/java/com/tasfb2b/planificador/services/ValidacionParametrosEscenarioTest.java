package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.controller.PlanificadorController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validaciones 400 de los parámetros de arranque de escenarios (antes fallaban EN SILENCIO:
 * k≤0 se degradaba a K=1 sin avisar, sa/ta inválidos caían a defaults). El front ahora recibe
 * un 400 con mensaje en vez de una simulación distinta a la pedida. El rango de fechaInicio se
 * valida contra el dataset (omitido aquí: sin DataLoader el helper no puede conocer el rango).
 */
class ValidacionParametrosEscenarioTest {

    @Test
    void parametrosInvalidosDevuelvenMensajeYValidosNull() {
        PlanificadorService service = serviceSinDataset();

        assertTrue(service.validarParametrosEscenario(0, null, null, null).contains("k"));
        assertTrue(service.validarParametrosEscenario(-5, null, null, null).contains("k"));
        assertTrue(service.validarParametrosEscenario(null, 0, null, null).contains("sa"));
        assertTrue(service.validarParametrosEscenario(null, null, -10, null).contains("ta"));
        assertNull(service.validarParametrosEscenario(14, 5, 10, null), "parámetros válidos");
        assertNull(service.validarParametrosEscenario(null, null, null, null), "todo opcional");
    }

    @Test
    void escenario2ConKInvalidoDevuelve400ConError() {
        PlanificadorController controller =
                new PlanificadorController(serviceSinDataset(), new PlanificadorProperties());

        ResponseEntity<Map<String, Object>> respuesta =
                controller.iniciarEsc2(0, "alns", null, null, null, null, null, false);

        assertEquals(400, respuesta.getStatusCode().value());
        assertTrue(respuesta.getBody().get("error").toString().contains("k"));
    }

    @Test
    void escenario3ConKInvalidoDevuelve400ConError() {
        PlanificadorController controller =
                new PlanificadorController(serviceSinDataset(), new PlanificadorProperties());

        ResponseEntity<Map<String, Object>> respuesta =
                controller.iniciarEsc3(-1, 0.20, "alns", null, null);

        assertEquals(400, respuesta.getStatusCode().value());
        assertTrue(respuesta.getBody().get("error").toString().contains("k"));
    }

    @Test
    void escenario2ConSaOTaInvalidosDevuelve400() {
        PlanificadorController controller =
                new PlanificadorController(serviceSinDataset(), new PlanificadorProperties());

        assertEquals(400, controller.iniciarEsc2(14, "alns", null, null, 0, null, null, false)
                .getStatusCode().value());
        assertEquals(400, controller.iniciarEsc2(14, "alns", null, null, null, -3, null, false)
                .getStatusCode().value());
    }

    // ----------------------------------------------------------------------- helpers

    private static PlanificadorService serviceSinDataset() {
        return new PlanificadorService(null, null, null, new JobsRegistry(),
                null, null, null, null, null);
    }
}
