package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.jobs.ConsultaJobsService;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;

import com.tasfb2b.planificador.controlador.ConsultaJobsController;
import com.tasfb2b.planificador.dto.jobs.TableroResponse;
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
    void jobInexistenteDevuelve404() {
        ConsultaJobsController controller = controllerCon(new RegistroJobs());
        assertEquals(404, controller.tableroJob("no-existe").getStatusCode().value());
    }

    @Test
    void jobSinBloquesTraeMetricasYTasasEnCeroYUltimoBloqueNull() {
        RegistroJobs jobs = new RegistroJobs();
        ConsultaJobsController controller = controllerCon(jobs);
        EstadoJob job = jobs.crear("2", 14);

        TableroResponse body = controller.tableroJob(job.getJobId()).getBody();
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

    private static ConsultaJobsController controllerCon(RegistroJobs jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        ConsultaJobsService jobQuery = new ConsultaJobsService(jobs, null);
        return new ConsultaJobsController(service, jobQuery, new TelemetriaSimulacionService(jobs));
    }
}
