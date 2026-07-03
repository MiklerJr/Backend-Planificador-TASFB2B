package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.trabajos.ConsultaTrabajosService;
import com.tasfb2b.planificador.servicios.trabajos.EstadoTrabajo;
import com.tasfb2b.planificador.servicios.trabajos.RegistroTrabajos;

import com.tasfb2b.planificador.controlador.ConsultaTrabajosController;
import com.tasfb2b.planificador.dto.trabajos.ListaTrabajosResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de {@code GET /jobs} (Tanda 1B): el cuerpo (antes armado a mano en el controller) ahora
 * es un {@link ListaTrabajosResponse} tipado con {@code total} y un {@code ResumenTrabajo} por job vivo.
 */
class ListaTrabajosEndpointTest {

    @Test
    void listaTodosLosTrabajosConSuResumen() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosController controller = controllerCon(jobs);
        EstadoTrabajo j1 = jobs.crear("2", 14);
        EstadoTrabajo j2 = jobs.crear("3", 75);

        ListaTrabajosResponse body = controller.listarTrabajos(false).getBody();
        assertEquals(2, body.getTotal());
        assertEquals(2, body.getJobs().size());

        List<String> ids = body.getJobs().stream()
                .map(ListaTrabajosResponse.ResumenTrabajo::getJobId)
                .collect(Collectors.toList());
        assertTrue(ids.contains(j1.getJobId()));
        assertTrue(ids.contains(j2.getJobId()));

        ListaTrabajosResponse.ResumenTrabajo resumen = body.getJobs().stream()
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
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosController controller = controllerCon(jobs);
        EstadoTrabajo operacion = jobs.crear("1", 1);
        operacion.enVivo = true;
        EstadoTrabajo simulacion = jobs.crear("1", 1);   // E1 normal: enVivo queda false (default)

        List<ListaTrabajosResponse.ResumenTrabajo> resumenes = controller.listarTrabajos(false).getBody().getJobs();

        ListaTrabajosResponse.ResumenTrabajo rOperacion = resumenes.stream()
                .filter(r -> r.getJobId().equals(operacion.getJobId()))
                .findFirst().orElseThrow();
        ListaTrabajosResponse.ResumenTrabajo rSimulacion = resumenes.stream()
                .filter(r -> r.getJobId().equals(simulacion.getJobId()))
                .findFirst().orElseThrow();

        assertTrue(rOperacion.isEnVivo(), "la operación día a día se reporta enVivo=true");
        assertFalse(rSimulacion.isEnVivo(), "el E1 de simulación se reporta enVivo=false");
    }

    @Test
    void listaVaciaCuandoNoHayTrabajos() {
        ListaTrabajosResponse body = controllerCon(new RegistroTrabajos()).listarTrabajos(false).getBody();
        assertEquals(0, body.getTotal());
        assertTrue(body.getJobs().isEmpty());
    }

    // ----------------------------------------------------------------------- helpers

    private static ConsultaTrabajosController controllerCon(RegistroTrabajos jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        ConsultaTrabajosService jobQuery = new ConsultaTrabajosService(jobs, null);
        return new ConsultaTrabajosController(service, jobQuery);
    }
}
