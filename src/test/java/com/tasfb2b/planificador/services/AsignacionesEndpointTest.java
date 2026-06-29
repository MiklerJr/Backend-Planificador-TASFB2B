package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.simulacion.AsignacionMaleta;
import com.tasfb2b.planificador.dto.simulacion.AsignacionesResponse;
import com.tasfb2b.planificador.dto.simulacion.BloqueSimulacion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Contrato de {@code GET /jobs/{id}/asignaciones} (Tanda 1B): 404 si el job no existe; cada item
 * lleva la asignación con la posición de su bloque; {@code aeropuerto}/{@code vueloId} reflejan los
 * filtros aplicados (null si no se filtró) y los filtros realmente recortan el resultado.
 */
class AsignacionesEndpointTest {

    @Test
    void jobInexistenteDevuelve404() {
        JobQueryController controller = controllerCon(new JobsRegistry());
        assertEquals(404, controller.asignacionesJob("no-existe", 0, null, null, false)
                .getStatusCode().value());
    }

    @Test
    void sinFiltrosDevuelveTodasYEchoaFiltrosNull() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);
        job.publicarBloque(bloqueConAsignacion(0, "2026-01-02T00:00", "2026-01-02T01:00"));

        AsignacionesResponse body = controller.asignacionesJob(job.getJobId(), 0, null, null, false)
                .getBody();
        assertEquals(job.getJobId(), body.getJobId());
        assertEquals(0, body.getDesde());
        assertNull(body.getAeropuerto());
        assertNull(body.getVueloId());
        assertEquals(1, body.getTotal());
        AsignacionesResponse.AsignacionItem item = body.getAsignaciones().get(0);
        assertEquals(0, item.getBloqueIdx());
        assertEquals("2026-01-02T00:00", item.getHoraInicio());
        assertEquals("B1", item.getAsignacion().getBatchId());
    }

    @Test
    void elFiltroPorAeropuertoRecortaYSeNormalizaAMayusculas() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);
        job.publicarBloque(bloqueConAsignacion(0, "2026-01-02T00:00", "2026-01-02T01:00"));

        // Coincide con el origen "SKBO" (se normaliza a mayúsculas).
        AsignacionesResponse coincide = controller.asignacionesJob(job.getJobId(), 0, "skbo", null, false)
                .getBody();
        assertEquals("SKBO", coincide.getAeropuerto());
        assertEquals(1, coincide.getTotal());

        // No coincide con ningún extremo de la asignación.
        AsignacionesResponse vacia = controller.asignacionesJob(job.getJobId(), 0, "SVMI", null, false)
                .getBody();
        assertEquals(0, vacia.getTotal());
    }

    // ----------------------------------------------------------------------- helpers

    private static BloqueSimulacion bloqueConAsignacion(int idx, String horaInicio, String horaFin) {
        BloqueSimulacion b = new BloqueSimulacion();
        b.setBloqueIdx(idx);
        b.setHoraInicio(horaInicio);
        b.setHoraFin(horaFin);
        AsignacionMaleta a = new AsignacionMaleta();
        a.setBatchId("B1");
        a.setOrigen("SKBO");
        a.setDestino("SEQM");
        a.setCantidad(50);
        a.setEnrutada(true);
        b.setAsignaciones(List.of(a));
        return b;
    }

    private static JobQueryController controllerCon(JobsRegistry jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        JobQueryService jobQuery = new JobQueryService(jobs, null);
        return new JobQueryController(service, jobQuery);
    }
}
