package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.trabajos.ConsultaTrabajosService;
import com.tasfb2b.planificador.servicios.trabajos.EstadoTrabajo;
import com.tasfb2b.planificador.servicios.trabajos.RegistroTrabajos;

import com.tasfb2b.planificador.controlador.ConsultaTrabajosController;
import com.tasfb2b.planificador.dto.simulacion.BloqueSimulacion;
import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacen;
import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacenFila;
import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacenesResponse;
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
    void trabajoInexistenteDevuelve404() {
        ConsultaTrabajosController controller = controllerCon(new RegistroTrabajos());
        assertEquals(404, controller.ocupacionAlmacenesTrabajo("no-existe", 0, 0).getStatusCode().value());
    }

    @Test
    void sinBloquesDevuelveListaVacia() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosController controller = controllerCon(jobs);
        EstadoTrabajo job = jobs.crear("3", 75);

        OcupacionAlmacenesResponse body = controller.ocupacionAlmacenesTrabajo(job.getJobId(), 0, 0).getBody();
        assertEquals(job.getJobId(), body.getJobId());
        assertEquals(0, body.getTotal());
        assertTrue(body.getAlmacenes().isEmpty());
    }

    @Test
    void cadaFilaLlevaLaOcupacionMasLaPosicionDelBloque() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosController controller = controllerCon(jobs);
        EstadoTrabajo job = jobs.crear("3", 75);
        job.publicarBloque(bloqueConOcupacion(2, "2026-01-02T05:00", "2026-01-02T06:00"));

        OcupacionAlmacenesResponse body = controller.ocupacionAlmacenesTrabajo(job.getJobId(), 0, 0).getBody();
        assertEquals(1, body.getTotal());
        OcupacionAlmacenFila row = body.getAlmacenes().get(0);
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

    private static ConsultaTrabajosController controllerCon(RegistroTrabajos jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        ConsultaTrabajosService jobQuery = new ConsultaTrabajosService(jobs, null);
        return new ConsultaTrabajosController(service, jobQuery);
    }
}
