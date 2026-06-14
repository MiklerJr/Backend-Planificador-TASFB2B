package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.JobsListResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de {@code GET /jobs} (Tanda 1B): el cuerpo (antes armado a mano en el controller) ahora
 * es un {@link JobsListResponse} tipado con {@code total} y un {@code JobResumen} por job vivo.
 */
class JobsListEndpointTest {

    @Test
    void listaTodosLosJobsConSuResumen() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState j1 = jobs.crear("2", 14);
        JobState j2 = jobs.crear("3", 75);

        JobsListResponse body = controller.listarJobs(false).getBody();
        assertEquals(2, body.getTotal());
        assertEquals(2, body.getJobs().size());

        List<String> ids = body.getJobs().stream()
                .map(JobsListResponse.JobResumen::getJobId)
                .collect(Collectors.toList());
        assertTrue(ids.contains(j1.getJobId()));
        assertTrue(ids.contains(j2.getJobId()));

        JobsListResponse.JobResumen resumen = body.getJobs().stream()
                .filter(r -> r.getJobId().equals(j2.getJobId()))
                .findFirst().orElseThrow();
        assertEquals("3", resumen.getEscenario());
        assertEquals(75, resumen.getK());
    }

    @Test
    void listaVaciaCuandoNoHayJobs() {
        JobsListResponse body = controllerCon(new JobsRegistry()).listarJobs(false).getBody();
        assertEquals(0, body.getTotal());
        assertTrue(body.getJobs().isEmpty());
    }

    // ----------------------------------------------------------------------- helpers

    private static JobQueryController controllerCon(JobsRegistry jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null, null);
        JobQueryService jobQuery = new JobQueryService(jobs, null);
        return new JobQueryController(service, jobQuery);
    }
}
