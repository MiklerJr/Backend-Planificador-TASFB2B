package com.tasfb2b.planificador.services;
import com.tasfb2b.planificador.services.ingesta.IngestaService;
import com.tasfb2b.planificador.services.jobs.JobsRegistry;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.controller.EscenarioController;
import com.tasfb2b.planificador.exception.ParametroInvalidoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validaciones de los parámetros de arranque de escenarios (antes fallaban EN SILENCIO: k≤0 se
 * degradaba a K=1 sin avisar, sa/ta inválidos caían a defaults). Hoy el controller lanza
 * {@link ParametroInvalidoException}, que el {@code GlobalExceptionHandler} traduce a un 400 con el
 * cuerpo {@code {"error": ...}} (ese mapeo HTTP se cubre en {@code GlobalExceptionHandlerTest}).
 * Aquí verificamos que la validación efectivamente DISPARA la excepción con el mensaje correcto.
 * El rango de fechaInicio se valida contra el dataset (omitido aquí: sin DataLoader el helper no
 * puede conocer el rango).
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
    void escenario2ConKInvalidoLanzaParametroInvalido() {
        EscenarioController controller =
                new EscenarioController(serviceSinDataset(), new PlanificadorProperties(),
                        new IngestaService(null, null, null, null, null, null));

        ParametroInvalidoException ex = assertThrows(ParametroInvalidoException.class,
                () -> controller.iniciarEsc2(0, "alns", null, null, null, null, null, false));
        assertTrue(ex.getMessage().contains("k"), "el mensaje debe mencionar el parámetro k");
    }

    @Test
    void escenario3ConKInvalidoLanzaParametroInvalido() {
        EscenarioController controller =
                new EscenarioController(serviceSinDataset(), new PlanificadorProperties(),
                        new IngestaService(null, null, null, null, null, null));

        ParametroInvalidoException ex = assertThrows(ParametroInvalidoException.class,
                () -> controller.iniciarEsc3(-1, 0.20, "alns", null, null));
        assertTrue(ex.getMessage().contains("k"), "el mensaje debe mencionar el parámetro k");
    }

    @Test
    void escenario2ConSaOTaInvalidosLanzaParametroInvalido() {
        EscenarioController controller =
                new EscenarioController(serviceSinDataset(), new PlanificadorProperties(),
                        new IngestaService(null, null, null, null, null, null));

        // k = null para saltar la verificación de k-fijo y caer en la validación de sa/ta.
        assertThrows(ParametroInvalidoException.class,
                () -> controller.iniciarEsc2(null, "alns", null, null, 0, null, null, false));
        assertThrows(ParametroInvalidoException.class,
                () -> controller.iniciarEsc2(null, "alns", null, null, null, -3, null, false));
    }

    // ----------------------------------------------------------------------- helpers

    private static PlanificadorService serviceSinDataset() {
        return new PlanificadorService(null, null, null, new JobsRegistry(),
                null, null);
    }
}
