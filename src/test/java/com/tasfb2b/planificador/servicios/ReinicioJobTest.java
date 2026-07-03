package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;

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
 * arranca en el executor async (y aquí falla por falta de cargadorDatos), pero las aserciones miran los
 * campos de identidad/parametrización, fijados de forma síncrona antes de encolar.
 */
class ReinicioJobTest {

    private static PlanificadorService serviceCon(RegistroJobs jobs) {
        // Solo props (para K fijo del escenario) y jobs son necesarios; el resto no se toca aquí.
        return new PlanificadorService(null, null, new PlanificadorProperties(), jobs,
                null, null);
    }

    @Test
    void jobInexistenteDevuelveNull() {
        PlanificadorService service = serviceCon(new RegistroJobs());
        assertNull(service.reiniciarJob("no-existe"));
    }

    @Test
    void reinicioCreaNuevoJobConMismosParametros() {
        RegistroJobs jobs = new RegistroJobs();
        PlanificadorService service = serviceCon(jobs);

        // Job E1 "anterior" con parámetros conocidos (creado sin ejecutar: queda encolado).
        EstadoJob viejo = jobs.crear("1", 1);
        viejo.algoritmo = "alns";
        viejo.seed = 42L;
        viejo.fechaInicio = LocalDateTime.of(2026, 1, 5, 0, 0);

        EstadoJob nuevo = service.reiniciarJob(viejo.getJobId());

        assertNotNull(nuevo);
        assertNotEquals(viejo.getJobId(), nuevo.getJobId());     // jobId NUEVO
        assertEquals("1", nuevo.getEscenario());
        assertEquals(42L, nuevo.seed);                            // MISMA seed (re-juego idéntico)
        assertEquals("alns", nuevo.algoritmo);
        assertEquals(viejo.fechaInicio, nuevo.fechaInicio);
        assertEquals("cancelado", viejo.estado);                  // el anterior se detuvo
    }
}
