package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.BloqueSimulacion;
import com.tasfb2b.planificador.dto.OcupacionAlmacen;
import com.tasfb2b.planificador.dto.OcupacionAlmacenRow;
import com.tasfb2b.planificador.dto.OcupacionAlmacenesResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de {@code GET /jobs/{id}/almacenes/ocupacion} (Tanda 1B): 404 si el job no existe; cada
 * fila es el pico concurrente ACUMULADO del almacén-día ({@link OcupacionAlmacen}) más
 * {@code bloqueIdx}/{@code horaInicio}/{@code horaFin} del bloque que la reportó.
 */
class OcupacionAlmacenesEndpointTest {

    @Test
    void jobInexistenteDevuelve404() {
        JobQueryController controller = controllerCon(new JobsRegistry());
        assertEquals(404, controller.ocupacionAlmacenesJob("no-existe").getStatusCode().value());
    }

    @Test
    void sinBloquesDevuelveListaVacia() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("3", 75);

        OcupacionAlmacenesResponse body = controller.ocupacionAlmacenesJob(job.getJobId()).getBody();
        assertEquals(job.getJobId(), body.getJobId());
        assertEquals(0, body.getTotal());
        assertTrue(body.getAlmacenes().isEmpty());
    }

    @Test
    void cadaFilaLlevaLaOcupacionMasLaPosicionDelBloque() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("3", 75);
        job.publicarBloque(bloqueConOcupacion(2, "2026-01-02T05:00", "2026-01-02T06:00"));

        OcupacionAlmacenesResponse body = controller.ocupacionAlmacenesJob(job.getJobId()).getBody();
        assertEquals(1, body.getTotal());
        OcupacionAlmacenRow row = body.getAlmacenes().get(0);
        assertEquals("SEQM", row.getAeropuerto());
        assertEquals("2026-01-02", row.getFecha());
        assertEquals(430, row.getCapacidadMaxima());
        assertEquals(117, row.getOcupacionAsignada());
        assertEquals("VERDE", row.getSemaforo());
        assertEquals(2, row.getBloqueIdx());
        assertEquals("2026-01-02T05:00", row.getHoraInicio());
        assertEquals("2026-01-02T06:00", row.getHoraFin());
    }

    // ----------------------------------------------------------------------- helpers

    private static BloqueSimulacion bloqueConOcupacion(int idx, String horaInicio, String horaFin) {
        BloqueSimulacion b = new BloqueSimulacion();
        b.setBloqueIdx(idx);
        b.setHoraInicio(horaInicio);
        b.setHoraFin(horaFin);
        OcupacionAlmacen o = new OcupacionAlmacen();
        o.setAeropuerto("SEQM");
        o.setFecha("2026-01-02");
        o.setCapacidadMaxima(430);
        o.setOcupacionAsignada(117);
        o.setPorcentajeOcupacion(27.2);
        o.setSemaforo("VERDE");
        b.setOcupacionAlmacenes(List.of(o));
        return b;
    }

    private static JobQueryController controllerCon(JobsRegistry jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null, null);
        JobQueryService jobQuery = new JobQueryService(jobs, null);
        return new JobQueryController(service, jobQuery);
    }
}
