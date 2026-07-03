package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.trabajos.ConsultaTrabajosService;
import com.tasfb2b.planificador.servicios.trabajos.EstadoTrabajo;
import com.tasfb2b.planificador.servicios.trabajos.RegistroTrabajos;

import com.tasfb2b.planificador.controlador.ConsultaTrabajosController;
import com.tasfb2b.planificador.dto.almacenes.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

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
        ConsultaTrabajosController controller = controllerCon(new RegistroTrabajos());
        assertEquals(404, controller.serieAlmacenesJob("no-existe", 0).getStatusCode().value());
    }

    @Test
    void paginaPorIndiceDeBloqueIgualQueBloques() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosController controller = controllerCon(jobs);
        EstadoTrabajo job = jobs.crear("2", 14);

        job.publicarSerieAlmacenes(List.of(slot("SKBO", "2026-01-02T13:00", 117)));
        job.publicarSerieAlmacenes(List.of(slot("SEQM", "2026-01-02T14:00", 80)));

        // desde=0: las dos series, con bloqueIdx alineado.
        SerieAlmacenesResponse body = controller.serieAlmacenesJob(job.getJobId(), 0).getBody();
        assertEquals(2, body.getTotal());
        List<SerieAlmacenesResponse.SerieItem> series = body.getSeries();
        assertEquals(2, series.size());
        assertEquals(0, series.get(0).getBloqueIdx());
        assertEquals(1, series.get(1).getBloqueIdx());

        // desde=1: solo la segunda.
        List<SerieAlmacenesResponse.SerieItem> desde1 =
                controller.serieAlmacenesJob(job.getJobId(), 1).getBody().getSeries();
        assertEquals(1, desde1.size());
        assertEquals(1, desde1.get(0).getBloqueIdx());
        List<OcupacionAlmacenSlot> slots = desde1.get(0).getSlots();
        assertEquals("SEQM", slots.get(0).getAeropuerto());

        // desde más allá de lo publicado: vacío pero con total vigente.
        ResponseEntity<SerieAlmacenesResponse> masAlla = controller.serieAlmacenesJob(job.getJobId(), 99);
        assertEquals(2, masAlla.getBody().getTotal());
        assertTrue(masAlla.getBody().getSeries().isEmpty());
    }

    // ----------------------------------------------------------------------- helpers

    private static ConsultaTrabajosController controllerCon(RegistroTrabajos jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        ConsultaTrabajosService jobQuery = new ConsultaTrabajosService(jobs, null);
        return new ConsultaTrabajosController(service, jobQuery);
    }

    private static OcupacionAlmacenSlot slot(String aeropuerto, String hora, int ocupacion) {
        OcupacionAlmacenSlot s = new OcupacionAlmacenSlot();
        s.setAeropuerto(aeropuerto);
        s.setHora(hora);
        s.setOcupacion(ocupacion);
        s.setCapacidadMaxima(430);
        return s;
    }
}
