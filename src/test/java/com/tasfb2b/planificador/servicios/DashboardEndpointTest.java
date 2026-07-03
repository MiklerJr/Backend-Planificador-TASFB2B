package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.trabajos.ConsultaTrabajosService;
import com.tasfb2b.planificador.servicios.trabajos.EstadoTrabajo;
import com.tasfb2b.planificador.servicios.trabajos.RegistroTrabajos;

import com.tasfb2b.planificador.controlador.ConsultaTrabajosController;
import com.tasfb2b.planificador.dto.trabajos.TableroResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Contrato de {@code GET /jobs/{id}/dashboard} (Tanda 1B): 404 si el job no existe; para un job sin
 * bloques publicados, métricas y tasas presentes (en cero) y {@code ultimoBloque} null (se emite así).
 */
class DashboardEndpointTest {

    @Test
    void trabajoInexistenteDevuelve404() {
        ConsultaTrabajosController controller = controllerCon(new RegistroTrabajos());
        assertEquals(404, controller.tableroTrabajo("no-existe").getStatusCode().value());
    }

    @Test
    void trabajoSinBloquesTraeMetricasYTasasEnCeroYUltimoBloqueNull() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosController controller = controllerCon(jobs);
        EstadoTrabajo job = jobs.crear("2", 14);

        TableroResponse body = controller.tableroTrabajo(job.getJobId()).getBody();
        assertEquals(job.getJobId(), body.getJobId());
        assertEquals("2", body.getEscenario());
        assertEquals(14, body.getK());
        assertEquals("encolado", body.getEstado());
        assertEquals(0, body.getBloquesPublicados());
        assertNotNull(body.getMetricas());
        assertNotNull(body.getTasas());
        assertEquals(0.0, body.getTasas().getEnrutamientoPct());
        assertNull(body.getUltimoBloque(), "sin bloques publicados el resumen es null");
    }

    // ----------------------------------------------------------------------- helpers

    private static ConsultaTrabajosController controllerCon(RegistroTrabajos jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        ConsultaTrabajosService jobQuery = new ConsultaTrabajosService(jobs, null);
        return new ConsultaTrabajosController(service, jobQuery);
    }
}
