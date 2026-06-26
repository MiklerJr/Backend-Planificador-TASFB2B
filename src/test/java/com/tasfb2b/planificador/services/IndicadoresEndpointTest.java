package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.BloqueSimulacion;
import com.tasfb2b.planificador.dto.CargaVuelo;
import com.tasfb2b.planificador.dto.IndicadoresResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de {@code GET /jobs/{id}/indicadores} (Tanda 1B): 404 si el job no existe; umbrales del
 * semáforo presentes (verde ≤ ámbar) y telemetría de vuelos/almacenes vacía para un job sin bloques.
 */
class IndicadoresEndpointTest {

    @Test
    void jobInexistenteDevuelve404() {
        JobQueryController controller = controllerCon(new JobsRegistry());
        assertEquals(404, controller.indicadoresJob("no-existe").getStatusCode().value());
    }

    @Test
    void traeUmbralesYTelemetriaVaciaSinBloques() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);

        IndicadoresResponse body = controller.indicadoresJob(job.getJobId()).getBody();
        assertEquals(job.getJobId(), body.getJobId());
        assertNotNull(body.getUmbrales());
        assertTrue(body.getUmbrales().getVerdeHasta() <= body.getUmbrales().getAmbarHasta());
        assertTrue(body.getVuelos().isEmpty());
        assertTrue(body.getAlmacenes().isEmpty());
    }

    @Test
    void tomaLosBloquesMasRecientesAcotadoPorElLimite() {
        JobsRegistry jobs = new JobsRegistry();
        // Config con tope de 1 fila ⇒ el snapshot debe quedarse con el bloque más reciente (tail).
        PlanificadorProperties props = new PlanificadorProperties();
        props.getConsulta().setMaxFilasPagina(1);
        JobQueryService jobQuery = new JobQueryService(jobs, null, null, null, props);
        JobQueryController controller = new JobQueryController(
                new PlanificadorService(null, null, null, jobs, null, null), jobQuery);

        JobState job = jobs.crear("2", 14);
        job.publicarBloque(bloqueConCarga(0, "viejo"));
        job.publicarBloque(bloqueConCarga(1, "reciente"));

        IndicadoresResponse body = controller.indicadoresJob(job.getJobId()).getBody();
        assertEquals(1, body.getVuelos().size(), "acotado al tope de filas");
        assertEquals(1, body.getVuelos().get(0).getBloqueIdx(), "es el bloque MÁS reciente, no el viejo");
        assertEquals("reciente", body.getVuelos().get(0).getVueloId());
    }

    // ----------------------------------------------------------------------- helpers

    private static BloqueSimulacion bloqueConCarga(int idx, String vueloId) {
        BloqueSimulacion b = new BloqueSimulacion();
        b.setBloqueIdx(idx);
        b.setHoraInicio("2026-01-02T0" + idx + ":00");
        b.setHoraFin("2026-01-02T0" + (idx + 1) + ":00");
        CargaVuelo c = new CargaVuelo();
        c.setVueloId(vueloId);
        c.setOrigen("SKBO");
        c.setDestino("SEQM");
        c.setCapacidadMaxima(300);
        c.setCargaAsignada(100);
        c.setPorcentajeCarga(33.3);
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
