package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.trabajos.EstadoTrabajo;
import com.tasfb2b.planificador.servicios.trabajos.ConsultaTrabajosService;
import com.tasfb2b.planificador.servicios.trabajos.RegistroTrabajos;

import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.dto.vuelos.VuelosUsadosResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanificadorVuelosUsadosTest {

    @Test
    void agregaEnviosYMaletasDelMismoVuelo() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosService jobQuery = jobQueryConJobs(jobs);
        EstadoTrabajo job = jobs.crear("2", 14);

        job.publicarBloque(bloque(0,
                asignacion("BATCH-001", 40, tramo("LA2450", "SPIM", "SKBO",
                        "2026-05-19T10:00:00", "2026-05-19T13:00:00")),
                asignacion("BATCH-002", 105, tramo("LA2450", "SPIM", "SKBO",
                        "2026-05-19T10:00:00", "2026-05-19T13:00:00"))));

        VuelosUsadosResponse response = jobQuery.getVuelosUsadosJob(job.getJobId(), 0);

        assertEquals(job.getJobId(), response.getJobId());
        assertEquals(1, response.getTotal());
        VuelosUsadosResponse.VueloUsado vuelo = response.getVuelos().get(0);
        assertEquals("LA2450|2026-05-19T10:00:00", vuelo.getFlightKey());
        assertEquals(145, vuelo.getCantidadMaletas());
        assertEquals(2, vuelo.getCantidadEnvios());
        assertEquals(List.of("BATCH-001", "BATCH-002"), vuelo.getEnvioIds());
    }

    @Test
    void distingueMismoVueloEnHorariosDistintos() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosService jobQuery = jobQueryConJobs(jobs);
        EstadoTrabajo job = jobs.crear("2", 14);

        job.publicarBloque(bloque(0,
                asignacion("BATCH-001", 20, tramo("LA2450", "SPIM", "SKBO",
                        "2026-05-19T10:00:00", "2026-05-19T13:00:00")),
                asignacion("BATCH-002", 30, tramo("LA2450", "SPIM", "SKBO",
                        "2026-05-20T10:00:00", "2026-05-20T13:00:00"))));

        VuelosUsadosResponse response = jobQuery.getVuelosUsadosJob(job.getJobId(), 0);

        assertEquals(2, response.getTotal());
        List<String> flightKeys = response.getVuelos().stream()
                .map(VuelosUsadosResponse.VueloUsado::getFlightKey)
                .toList();
        assertEquals(List.of(
                "LA2450|2026-05-19T10:00:00",
                "LA2450|2026-05-20T10:00:00"), flightKeys);
    }

    @Test
    void desdeMayorABloquesPublicadosDevuelveListaVacia() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosService jobQuery = jobQueryConJobs(jobs);
        EstadoTrabajo job = jobs.crear("2", 14);

        job.publicarBloque(bloque(0,
                asignacion("BATCH-001", 20, tramo("LA2450", "SPIM", "SKBO",
                        "2026-05-19T10:00:00", "2026-05-19T13:00:00"))));

        VuelosUsadosResponse response = jobQuery.getVuelosUsadosJob(job.getJobId(), 99);

        assertEquals(99, response.getDesde());
        assertEquals(1, response.getBloquesPublicados());
        assertEquals(0, response.getTotal());
        assertTrue(response.getVuelos().isEmpty());
    }

    /**
     * Regresión del eje temporal: flightKey, fechaSalida y fechaLlegada deben salir de
     * salidaUtc/llegadaUtc (eje global del mapa), NO de las horas locales (que mezclan husos:
     * salida local del origen, llegada local del destino). El helper pone las locales a +5h.
     */
    @Test
    void flightKeyYFechasUsanElEjeUtcNoElLocal() {
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosService jobQuery = jobQueryConJobs(jobs);
        EstadoTrabajo job = jobs.crear("2", 14);

        TramoRuta tramo = tramo("LA2450", "SPIM", "SKBO",
                "2026-05-19T10:00:00", "2026-05-19T13:00:00");
        job.publicarBloque(bloque(0, asignacion("BATCH-001", 40, tramo)));

        VuelosUsadosResponse.VueloUsado vuelo =
                jobQuery.getVuelosUsadosJob(job.getJobId(), 0).getVuelos().get(0);

        assertEquals("2026-05-19T10:00:00", vuelo.getFechaSalida(), "fechaSalida = salidaUtc");
        assertEquals("2026-05-19T13:00:00", vuelo.getFechaLlegada(), "fechaLlegada = llegadaUtc");
        assertEquals("LA2450|2026-05-19T10:00:00", vuelo.getFlightKey(), "flightKey en eje UTC");
        assertEquals(LocalDateTime.of(2026, 5, 19, 15, 0), LocalDateTime.parse(tramo.getSalidaLocal()),
                "sanidad: la hora local difiere de la UTC, así que el eje queda fijado");
    }

    private static ConsultaTrabajosService jobQueryConJobs(RegistroTrabajos jobs) {
        // getVuelosUsadosJob solo usa el registry; CargadorDatos no interviene → null.
        return new ConsultaTrabajosService(jobs, null);
    }

    private static BloqueSimulacion bloque(
            int idx,
            AsignacionMaleta... asignaciones) {
        BloqueSimulacion bloque = new BloqueSimulacion();
        bloque.setBloqueIdx(idx);
        bloque.setHoraInicio("2026-05-19T08:00:00");
        bloque.setHoraFin("2026-05-19T09:00:00");
        bloque.setAsignaciones(List.of(asignaciones));
        return bloque;
    }

    private static AsignacionMaleta asignacion(
            String batchId,
            int cantidad,
            TramoRuta... tramos) {
        AsignacionMaleta asignacion = new AsignacionMaleta();
        asignacion.setBatchId(batchId);
        asignacion.setOrigen("SPIM");
        asignacion.setDestino("SKBO");
        asignacion.setCantidad(cantidad);
        asignacion.setEnrutada(true);
        asignacion.setTramos(List.of(tramos));
        return asignacion;
    }

    /**
     * Tramo con salida/llegada en UTC (el eje que usa el endpoint) y horas LOCALES deliberadamente
     * DISTINTAS (+5h): si vuelos/usados volviera a leer los {@code *Local}, los flightKey y fechas
     * esperados por estos tests dejarían de coincidir (regresión del eje temporal).
     */
    private static TramoRuta tramo(
            String vueloId,
            String origen,
            String destino,
            String salidaUtc,
            String llegadaUtc) {
        TramoRuta tramo = new TramoRuta();
        tramo.setVueloId(vueloId);
        tramo.setOrigen(origen);
        tramo.setDestino(destino);
        tramo.setSalidaUtc(salidaUtc);
        tramo.setLlegadaUtc(llegadaUtc);
        tramo.setSalidaLocal(LocalDateTime.parse(salidaUtc).plusHours(5).toString());
        tramo.setLlegadaLocal(LocalDateTime.parse(llegadaUtc).plusHours(5).toString());
        return tramo;
    }
}
