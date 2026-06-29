package com.tasfb2b.planificador.services;
import com.tasfb2b.planificador.services.jobs.JobQueryService;
import com.tasfb2b.planificador.services.jobs.JobState;
import com.tasfb2b.planificador.services.jobs.JobsRegistry;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.simulacion.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contrato de {@code GET /jobs/{jobId}/estado-inicial}: 404 si el job no existe; 204 mientras el
 * snapshot no está calculado (job encolado/calentando); 200 con las asignaciones activas al
 * llegar a fechaInicio (lista vacía si el job no tuvo warm-up, p. ej. E2 que arranca en frío).
 */
class EstadoInicialEndpointTest {

    @Test
    void jobInexistenteDevuelve404() {
        JobQueryController controller = controllerCon(new JobsRegistry());
        assertEquals(404, controller.estadoInicialJob("no-existe").getStatusCode().value());
    }

    @Test
    void mientrasNoHaySnapshotDevuelve204() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("1", 1);   // recién creado: estadoInicial aún null

        assertEquals(204, controller.estadoInicialJob(job.getJobId()).getStatusCode().value());
    }

    @Test
    void conSnapshotDevuelveLasAsignacionesActivas() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("3", 75);

        AsignacionMaleta enElAire = new AsignacionMaleta();
        enElAire.setBatchId("B1");
        enElAire.setEnrutada(true);
        job.estadoInicial = List.of(enElAire);

        ResponseEntity<EstadoInicialResponse> respuesta = controller.estadoInicialJob(job.getJobId());
        assertEquals(200, respuesta.getStatusCode().value());
        assertEquals(1, respuesta.getBody().getTotal());
        List<AsignacionMaleta> asignaciones = respuesta.getBody().getAsignaciones();
        assertEquals("B1", asignaciones.get(0).getBatchId());
    }

    @Test
    void jobSinWarmupDevuelveListaVacia() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);
        job.estadoInicial = List.of();   // E2 (o E1/E3 sin fechaInicio): sin warm-up

        ResponseEntity<EstadoInicialResponse> respuesta = controller.estadoInicialJob(job.getJobId());
        assertEquals(200, respuesta.getStatusCode().value());
        assertEquals(0, respuesta.getBody().getTotal());
    }

    // ----------------------------------------------------------------------- helpers

    private static JobQueryController controllerCon(JobsRegistry jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        JobQueryService jobQuery = new JobQueryService(jobs, null);
        return new JobQueryController(service, jobQuery);
    }
}
