package com.tasfb2b.planificador.services;
import com.tasfb2b.planificador.services.jobs.JobQueryService;
import com.tasfb2b.planificador.services.jobs.JobState;
import com.tasfb2b.planificador.services.jobs.JobsRegistry;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.jobs.EstadoJobResponse;
import com.tasfb2b.planificador.dto.vuelos.VueloCancelado;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verificación del fix "cancelaciones invisibles para el front": las cancelaciones de vuelo YA
 * aplicadas por el motor viven en {@code JobState.vuelosCancelados} (el bucle del job usa esa
 * lista como registro de {@code aplicarCancelacionesVuelo}) y {@code GET /jobs/{id}/estado} las
 * expone con sus envíos afectados. Antes solo eran visibles en el CSV de auditoría final, así
 * que un mapa seguía animando vuelo-días que ya no existían.
 */
class EstadoJobVuelosCanceladosTest {

    @Test
    void estadoDelJobExponeLasCancelacionesAplicadasConSusEnviosAfectados() {
        JobsRegistry jobs = new JobsRegistry();
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        JobQueryController controller = new JobQueryController(service, new JobQueryService(jobs, null));
        JobState job = jobs.crear("2", 14);

        // El worker registra una cancelación aplicada (misma lista que recibe
        // aplicarCancelacionesVuelo como `registro` cuando hay job).
        job.getVuelosCancelados().add(new VueloCancelado(
                "SKBO", "SEQM", LocalDateTime.of(2026, 1, 3, 14, 30), 7));

        ResponseEntity<EstadoJobResponse> respuesta = controller.estadoJob(job.getJobId());
        assertEquals(200, respuesta.getStatusCode().value());

        List<VueloCancelado> cancelados = respuesta.getBody().getVuelosCancelados();
        assertEquals(1, cancelados.size());
        VueloCancelado vc = cancelados.get(0);
        assertEquals("SKBO", vc.getOrigen());
        assertEquals("SEQM", vc.getDestino());
        assertEquals(LocalDateTime.of(2026, 1, 3, 14, 30), vc.getFechaHoraSalida());
        assertEquals(7, vc.getEnviosAfectados());
    }

    @Test
    void unJobSinCancelacionesExponeLaListaVacia() {
        JobsRegistry jobs = new JobsRegistry();
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        JobQueryController controller = new JobQueryController(service, new JobQueryService(jobs, null));
        JobState job = jobs.crear("3", 75);

        EstadoJobResponse body = controller.estadoJob(job.getJobId()).getBody();

        assertTrue(body.getVuelosCancelados().isEmpty(),
                "el campo existe siempre, vacío si no hubo cancelaciones");
    }
}
