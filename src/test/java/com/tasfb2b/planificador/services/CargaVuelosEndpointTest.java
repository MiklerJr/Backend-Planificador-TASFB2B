package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.BloqueSimulacion;
import com.tasfb2b.planificador.dto.CargaVuelo;
import com.tasfb2b.planificador.dto.CargaVueloRow;
import com.tasfb2b.planificador.dto.CargaVuelosResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de {@code GET /jobs/{id}/vuelos/carga} (Tanda 1B): 404 si el job no existe; cada fila es
 * la carga ACUMULADA del vuelo-día ({@link CargaVuelo}) más {@code bloqueIdx}/{@code horaInicio}/
 * {@code horaFin} del bloque que la reportó. El DTO conserva los mismos campos del mapa anterior.
 */
class CargaVuelosEndpointTest {

    @Test
    void jobInexistenteDevuelve404() {
        JobQueryController controller = controllerCon(new JobsRegistry());
        assertEquals(404, controller.cargaVuelosJob("no-existe").getStatusCode().value());
    }

    @Test
    void sinBloquesDevuelveListaVacia() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);

        CargaVuelosResponse body = controller.cargaVuelosJob(job.getJobId()).getBody();
        assertEquals(job.getJobId(), body.getJobId());
        assertEquals(0, body.getTotal());
        assertTrue(body.getVuelos().isEmpty());
    }

    @Test
    void cadaFilaLlevaLaCargaDelVueloMasLaPosicionDelBloque() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);
        job.publicarBloque(bloqueConCarga(0, "2026-01-02T00:00", "2026-01-02T01:00"));

        CargaVuelosResponse body = controller.cargaVuelosJob(job.getJobId()).getBody();
        assertEquals(1, body.getTotal());
        CargaVueloRow row = body.getVuelos().get(0);
        assertEquals("1501", row.getVueloId());
        assertEquals("SKBO", row.getOrigen());
        assertEquals("SEQM", row.getDestino());
        assertEquals(300, row.getCapacidadMaxima());
        assertEquals(145, row.getCargaAsignada());
        assertEquals("VERDE", row.getSemaforo());
        // Contexto del bloque que la reportó.
        assertEquals(0, row.getBloqueIdx());
        assertEquals("2026-01-02T00:00", row.getHoraInicio());
        assertEquals("2026-01-02T01:00", row.getHoraFin());
    }

    // ----------------------------------------------------------------------- helpers

    private static BloqueSimulacion bloqueConCarga(int idx, String horaInicio, String horaFin) {
        BloqueSimulacion b = new BloqueSimulacion();
        b.setBloqueIdx(idx);
        b.setHoraInicio(horaInicio);
        b.setHoraFin(horaFin);
        CargaVuelo c = new CargaVuelo();
        c.setVueloId("1501");
        c.setOrigen("SKBO");
        c.setDestino("SEQM");
        c.setCapacidadMaxima(300);
        c.setCargaAsignada(145);
        c.setPorcentajeCarga(48.33);
        c.setSemaforo("VERDE");
        b.setCargasVuelos(List.of(c));
        return b;
    }

    private static JobQueryController controllerCon(JobsRegistry jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        JobQueryService jobQuery = new JobQueryService(jobs, null);
        return new JobQueryController(service, jobQuery);
    }
}
