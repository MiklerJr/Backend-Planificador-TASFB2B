package com.tasfb2b.planificador.services;
import com.tasfb2b.planificador.services.jobs.JobQueryService;
import com.tasfb2b.planificador.services.jobs.JobState;
import com.tasfb2b.planificador.services.jobs.JobsRegistry;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.jobs.DashboardResponse;
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
        JobQueryController controller = controllerCon(new JobsRegistry());
        assertEquals(404, controller.dashboardJob("no-existe").getStatusCode().value());
    }

    @Test
    void jobSinBloquesTraeMetricasYTasasEnCeroYUltimoBloqueNull() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);

        DashboardResponse body = controller.dashboardJob(job.getJobId()).getBody();
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

    private static JobQueryController controllerCon(JobsRegistry jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        JobQueryService jobQuery = new JobQueryService(jobs, null);
        return new JobQueryController(service, jobQuery);
    }
}
