package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.jobs.ConsultaJobsService;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;

import com.tasfb2b.planificador.controlador.ConsultaJobsController;
import com.tasfb2b.planificador.dto.jobs.ListaJobsResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de {@code GET /jobs} (Tanda 1B): el cuerpo (antes armado a mano en el controller) ahora
 * es un {@link ListaJobsResponse} tipado con {@code total} y un {@code ResumenJob} por job vivo.
 */
class ListaJobsEndpointTest {

    @Test
    void listaTodosLosJobsConSuResumen() {
        RegistroJobs jobs = new RegistroJobs();
        ConsultaJobsController controller = controllerCon(jobs);
        EstadoJob j1 = jobs.crear("2", 14);
        EstadoJob j2 = jobs.crear("3", 75);

        ListaJobsResponse body = controller.listarJobs(false).getBody();
        assertEquals(2, body.getTotal());
        assertEquals(2, body.getJobs().size());

        List<String> ids = body.getJobs().stream()
                .map(ListaJobsResponse.ResumenJob::getJobId)
                .collect(Collectors.toList());
        assertTrue(ids.contains(j1.getJobId()));
        assertTrue(ids.contains(j2.getJobId()));

        ListaJobsResponse.ResumenJob resumen = body.getJobs().stream()
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
        RegistroJobs jobs = new RegistroJobs();
        ConsultaJobsController controller = controllerCon(jobs);
        EstadoJob operacion = jobs.crear("1", 1);
        operacion.enVivo = true;
        EstadoJob simulacion = jobs.crear("1", 1);   // E1 normal: enVivo queda false (default)

        List<ListaJobsResponse.ResumenJob> resumenes = controller.listarJobs(false).getBody().getJobs();

        ListaJobsResponse.ResumenJob rOperacion = resumenes.stream()
                .filter(r -> r.getJobId().equals(operacion.getJobId()))
                .findFirst().orElseThrow();
        ListaJobsResponse.ResumenJob rSimulacion = resumenes.stream()
                .filter(r -> r.getJobId().equals(simulacion.getJobId()))
                .findFirst().orElseThrow();

        assertTrue(rOperacion.isEnVivo(), "la operación día a día se reporta enVivo=true");
        assertFalse(rSimulacion.isEnVivo(), "el E1 de simulación se reporta enVivo=false");
    }

    @Test
    void listaVaciaCuandoNoHayJobs() {
        ListaJobsResponse body = controllerCon(new RegistroJobs()).listarJobs(false).getBody();
        assertEquals(0, body.getTotal());
        assertTrue(body.getJobs().isEmpty());
    }

    // ----------------------------------------------------------------------- helpers

    private static ConsultaJobsController controllerCon(RegistroJobs jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        ConsultaJobsService jobQuery = new ConsultaJobsService(jobs, null);
        return new ConsultaJobsController(service, jobQuery);
    }
}
