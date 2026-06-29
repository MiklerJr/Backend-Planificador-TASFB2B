package com.tasfb2b.planificador.services;
import com.tasfb2b.planificador.services.jobs.JobQueryService;
import com.tasfb2b.planificador.services.jobs.JobState;
import com.tasfb2b.planificador.services.jobs.JobsRegistry;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.jobs.JobsListResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * El resumen debe exponer {@code enVivo} para que el front auto-detecte la operación día a día
     * (E1 en vivo) frente a un E1 de simulación, sin caer al filtro ambiguo por {@code escenario=="1"}.
     */
    @Test
    void resumenExponeEnVivo() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState operacion = jobs.crear("1", 1);
        operacion.enVivo = true;
        JobState simulacion = jobs.crear("1", 1);   // E1 normal: enVivo queda false (default)

        List<JobsListResponse.JobResumen> resumenes = controller.listarJobs(false).getBody().getJobs();

        JobsListResponse.JobResumen rOperacion = resumenes.stream()
                .filter(r -> r.getJobId().equals(operacion.getJobId()))
                .findFirst().orElseThrow();
        JobsListResponse.JobResumen rSimulacion = resumenes.stream()
                .filter(r -> r.getJobId().equals(simulacion.getJobId()))
                .findFirst().orElseThrow();

        assertTrue(rOperacion.isEnVivo(), "la operación día a día se reporta enVivo=true");
        assertFalse(rSimulacion.isEnVivo(), "el E1 de simulación se reporta enVivo=false");
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
                null, null);
        JobQueryService jobQuery = new JobQueryService(jobs, null);
        return new JobQueryController(service, jobQuery);
    }
}
