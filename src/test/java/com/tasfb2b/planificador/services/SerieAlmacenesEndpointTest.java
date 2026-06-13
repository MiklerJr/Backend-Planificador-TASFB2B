package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.controller.PlanificadorController;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de {@code GET /jobs/{jobId}/almacenes/serie?desde=N}: 404 si el job no existe;
 * paginación por índice de bloque (idéntica a /bloques: el front guarda {@code total} y vuelve a
 * pedir con {@code desde = total}); cada fila trae {@code bloqueIdx} y sus slots en orden.
 */
class SerieAlmacenesEndpointTest {

    @Test
    void jobInexistenteDevuelve404() {
        PlanificadorController controller = controllerCon(new JobsRegistry());
        assertEquals(404, controller.serieAlmacenesJob("no-existe", 0).getStatusCode().value());
    }

    @Test
    void paginaPorIndiceDeBloqueIgualQueBloques() {
        JobsRegistry jobs = new JobsRegistry();
        PlanificadorController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);

        job.publicarSerieAlmacenes(List.of(slot("SKBO", "2026-01-02T13:00", 117)));
        job.publicarSerieAlmacenes(List.of(slot("SEQM", "2026-01-02T14:00", 80)));

        // desde=0: las dos series, con bloqueIdx alineado.
        Map<String, Object> body = controller.serieAlmacenesJob(job.getJobId(), 0).getBody();
        assertEquals(2, body.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) body.get("series");
        assertEquals(2, series.size());
        assertEquals(0, series.get(0).get("bloqueIdx"));
        assertEquals(1, series.get(1).get("bloqueIdx"));

        // desde=1: solo la segunda.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> desde1 =
                (List<Map<String, Object>>) controller.serieAlmacenesJob(job.getJobId(), 1)
                        .getBody().get("series");
        assertEquals(1, desde1.size());
        assertEquals(1, desde1.get(0).get("bloqueIdx"));
        @SuppressWarnings("unchecked")
        List<SimulacionResponse.OcupacionAlmacenSlot> slots =
                (List<SimulacionResponse.OcupacionAlmacenSlot>) desde1.get(0).get("slots");
        assertEquals("SEQM", slots.get(0).getAeropuerto());

        // desde más allá de lo publicado: vacío pero con total vigente.
        ResponseEntity<Map<String, Object>> masAlla = controller.serieAlmacenesJob(job.getJobId(), 99);
        assertEquals(2, masAlla.getBody().get("total"));
        assertTrue(((List<?>) masAlla.getBody().get("series")).isEmpty());
    }

    // ----------------------------------------------------------------------- helpers

    private static PlanificadorController controllerCon(JobsRegistry jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null, null, null, null);
        return new PlanificadorController(service, new PlanificadorProperties());
    }

    private static SimulacionResponse.OcupacionAlmacenSlot slot(String aeropuerto, String hora, int ocupacion) {
        SimulacionResponse.OcupacionAlmacenSlot s = new SimulacionResponse.OcupacionAlmacenSlot();
        s.setAeropuerto(aeropuerto);
        s.setHora(hora);
        s.setOcupacion(ocupacion);
        s.setCapacidadMaxima(430);
        return s;
    }
}
