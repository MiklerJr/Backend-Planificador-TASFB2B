package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.IndicadoresResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de {@code GET /jobs/{id}/indicadores} (Tanda 1B): 404 si el job no existe; umbrales del
 * semáforo presentes (verde ≤ ámbar) y telemetría de vuelos/almacenes vacía para un job sin bloques.
 */
class IndicadoresEndpointTest {

    @Test
    void jobInexistenteDevuelve404() {
        JobQueryController controller = controllerCon(new JobsRegistry());
        assertEquals(404, controller.indicadoresJob("no-existe").getStatusCode().value());
    }

    @Test
    void traeUmbralesYTelemetriaVaciaSinBloques() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);

        IndicadoresResponse body = controller.indicadoresJob(job.getJobId()).getBody();
        assertEquals(job.getJobId(), body.getJobId());
        assertNotNull(body.getUmbrales());
        assertTrue(body.getUmbrales().getVerdeHasta() <= body.getUmbrales().getAmbarHasta());
        assertTrue(body.getVuelos().isEmpty());
        assertTrue(body.getAlmacenes().isEmpty());
    }

    // ----------------------------------------------------------------------- helpers

    private static JobQueryController controllerCon(JobsRegistry jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null, null);
        JobQueryService jobQuery = new JobQueryService(jobs, null);
        return new JobQueryController(service, jobQuery);
    }
}
