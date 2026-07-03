package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.trabajos.EstadoTrabajo;
import com.tasfb2b.planificador.servicios.trabajos.RegistroTrabajos;

import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code reiniciarJob}: detiene el job anterior y crea uno nuevo con jobId distinto pero los mismos
 * parámetros reproducibles (misma seed, escenario, algoritmo, fechaInicio). El worker del job nuevo
 * arranca en el executor async (y aquí falla por falta de dataLoader), pero las aserciones miran los
 * campos de identidad/parametrización, fijados de forma síncrona antes de encolar.
 */
class ReinicioTrabajoTest {

    private static PlanificadorService serviceCon(RegistroTrabajos jobs) {
        // Solo props (para K fijo del escenario) y jobs son necesarios; el resto no se toca aquí.
        return new PlanificadorService(null, null, new PlanificadorProperties(), jobs,
                null, null);
    }

    @Test
    void jobInexistenteDevuelveNull() {
        PlanificadorService service = serviceCon(new RegistroTrabajos());
        assertNull(service.reiniciarJob("no-existe"));
    }

    @Test
    void reinicioCreaNuevoJobConMismosParametros() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        PlanificadorService service = serviceCon(jobs);

        // Job E1 "anterior" con parámetros conocidos (creado sin ejecutar: queda encolado).
        EstadoTrabajo viejo = jobs.crear("1", 1);
        viejo.algoritmo = "alns";
        viejo.seed = 42L;
        viejo.fechaInicio = LocalDateTime.of(2026, 1, 5, 0, 0);

        EstadoTrabajo nuevo = service.reiniciarJob(viejo.getJobId());

        assertNotNull(nuevo);
        assertNotEquals(viejo.getJobId(), nuevo.getJobId());     // jobId NUEVO
        assertEquals("1", nuevo.getEscenario());
        assertEquals(42L, nuevo.seed);                            // MISMA seed (re-juego idéntico)
        assertEquals("alns", nuevo.algoritmo);
        assertEquals(viejo.fechaInicio, nuevo.fechaInicio);
        assertEquals("cancelado", viejo.estado);                  // el anterior se detuvo
    }
}
