package com.tasfb2b.planificador.services;
import com.tasfb2b.planificador.services.jobs.JobQueryService;
import com.tasfb2b.planificador.services.jobs.JobState;
import com.tasfb2b.planificador.services.jobs.JobsRegistry;

import com.tasfb2b.planificador.controller.JobQueryController;
import com.tasfb2b.planificador.dto.simulacion.BloqueSimulacion;
import com.tasfb2b.planificador.dto.vuelos.CargaVuelo;
import com.tasfb2b.planificador.dto.vuelos.CargaVueloRow;
import com.tasfb2b.planificador.dto.vuelos.CargaVuelosResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(404, controller.cargaVuelosJob("no-existe", 0, 0).getStatusCode().value());
    }

    @Test
    void sinBloquesDevuelveListaVacia() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);

        CargaVuelosResponse body = controller.cargaVuelosJob(job.getJobId(), 0, 0).getBody();
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

        CargaVuelosResponse body = controller.cargaVuelosJob(job.getJobId(), 0, 0).getBody();
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

    @Test
    void paginaPorBloquesRecorriendoTodoSinPerderNiDuplicar() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);
        // 3 bloques con 2+1+2 = 5 filas; vueloId único por fila para detectar duplicados/pérdidas.
        job.publicarBloque(bloqueConCargas(0, 2));
        job.publicarBloque(bloqueConCargas(1, 1));
        job.publicarBloque(bloqueConCargas(2, 2));

        // Recorre páginas con limit=1: incluye bloques COMPLETOS, así que cada página ≥1 fila y
        // puede exceder el limit (no se parte un bloque entre páginas).
        List<String> vistos = new ArrayList<>();
        Set<Integer> bloquesVistos = new HashSet<>();
        int desde = 0, paginas = 0;
        boolean hayMas = true;
        while (hayMas && paginas < 100) {
            CargaVuelosResponse pag = controller.cargaVuelosJob(job.getJobId(), desde, 1).getBody();
            assertEquals(desde, pag.getDesde());
            assertEquals(3, pag.getBloquesPublicados());
            assertTrue(pag.getTotal() >= 1, "una página de la ventana RAM nunca es vacía si quedan datos");
            assertEquals(pag.getVuelos().size(), pag.getTotal());
            for (CargaVueloRow r : pag.getVuelos()) {
                vistos.add(r.getVueloId());
                bloquesVistos.add(r.getBloqueIdx());
            }
            assertTrue(pag.getProximoDesde() > desde, "el cursor debe avanzar");
            hayMas = pag.isHayMas();
            desde = pag.getProximoDesde();
            paginas++;
        }

        assertFalse(hayMas, "el recorrido termina con hayMas=false");
        assertEquals(5, vistos.size(), "sin pérdidas: todas las filas");
        assertEquals(5, new HashSet<>(vistos).size(), "sin duplicados: vueloId únicos");
        assertEquals(Set.of(0, 1, 2), bloquesVistos, "los 3 bloques quedaron cubiertos");
    }

    @Test
    void limitSeClampeaAlTopeDelServidor() {
        JobsRegistry jobs = new JobsRegistry();
        JobQueryController controller = controllerCon(jobs);
        JobState job = jobs.crear("2", 14);
        job.publicarBloque(bloqueConCargas(0, 2));

        // limit gigante: el servicio lo clampea a maxFilasPagina (5000 por defecto en tests) ⇒ no OOM.
        CargaVuelosResponse pag = controller.cargaVuelosJob(job.getJobId(), 0, Integer.MAX_VALUE).getBody();
        assertEquals(2, pag.getTotal());
        assertFalse(pag.isHayMas());
    }

    // ----------------------------------------------------------------------- helpers

    private static BloqueSimulacion bloqueConCargas(int idx, int n) {
        BloqueSimulacion b = new BloqueSimulacion();
        b.setBloqueIdx(idx);
        b.setHoraInicio("2026-01-02T0" + idx + ":00");
        b.setHoraFin("2026-01-02T0" + (idx + 1) + ":00");
        List<CargaVuelo> cargas = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            CargaVuelo c = new CargaVuelo();
            c.setVueloId(String.valueOf(idx * 10 + j));   // único entre bloques/cargas
            c.setOrigen("SKBO");
            c.setDestino("SEQM");
            c.setCapacidadMaxima(300);
            c.setCargaAsignada(100);
            c.setPorcentajeCarga(33.3);
            c.setSemaforo("VERDE");
            cargas.add(c);
        }
        b.setCargasVuelos(cargas);
        return b;
    }

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
