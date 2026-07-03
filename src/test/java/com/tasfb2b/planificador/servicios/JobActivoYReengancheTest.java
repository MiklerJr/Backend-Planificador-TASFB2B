package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.controlador.ConsultaJobsController;
import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacenSlot;
import com.tasfb2b.planificador.dto.almacenes.SerieAlmacenesResponse;
import com.tasfb2b.planificador.dto.jobs.EstadoJobResponse;
import com.tasfb2b.planificador.dto.jobs.JobActivoResponse;
import com.tasfb2b.planificador.dto.simulacion.BloqueSimulacion;
import com.tasfb2b.planificador.servicios.jobs.ConsultaJobsService;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reenganche multi-cliente y temporizador real compartido:
 * <ul>
 *   <li>{@code GET /jobs/activo}: un cliente que recién se conecta obtiene el job en curso (o
 *       {@code activo=false}) en un solo round-trip.</li>
 *   <li>{@code GET /jobs/{id}/bloques} con {@code desde} ya purgado del buffer devuelve
 *       {@code bloques=[]} + {@code primerBloqueDisponible} (antes realineaba en silencio y
 *       soltaba la ventana retenida de bloques viejos, que el front mezclaba con los nuevos).</li>
 *   <li>El temporizador arranca al publicarse el primer bloque y se congela al terminar; lo
 *       calcula el servidor, así todos los clientes ven el mismo valor.</li>
 * </ul>
 */
class JobActivoYReengancheTest {

    // ------------------------------------------------------------------ GET /jobs/activo

    @Test
    void sinJobsDevuelveActivoFalseSinJobId() {
        JobActivoResponse body = controllerCon(new RegistroJobs()).jobActivo().getBody();
        assertFalse(body.isActivo());
        assertNull(body.getJobId());
    }

    @Test
    void prefiereElJobEjecutandoSobreLosEncolados() {
        RegistroJobs jobs = new RegistroJobs();
        ConsultaJobsController controller = controllerCon(jobs);
        jobs.crear("2", 5);                        // queda "encolado" (más antiguo)
        EstadoJob corriendo = jobs.crear("3", 144);
        corriendo.estado = "ejecutando";

        JobActivoResponse body = controller.jobActivo().getBody();
        assertTrue(body.isActivo());
        assertEquals(corriendo.getJobId(), body.getJobId());
        assertEquals("3", body.getEscenario());
        assertEquals("ejecutando", body.getEstado());
    }

    @Test
    void soloEncoladosDevuelveElMasAntiguo() {
        RegistroJobs jobs = new RegistroJobs();
        EstadoJob primero = jobs.crear("2", 5);
        jobs.crear("2", 5);

        JobActivoResponse body = controllerCon(jobs).jobActivo().getBody();
        assertTrue(body.isActivo());
        assertEquals(primero.getJobId(), body.getJobId());
    }

    @Test
    void jobActivoTraeLoNecesarioParaEngancharseAlStream() {
        RegistroJobs jobs = new RegistroJobs();
        EstadoJob job = jobs.crear("2", 5);
        job.estado = "ejecutando";
        job.setMaxBloquesConAsignaciones(3);
        for (int i = 0; i < 5; i++) job.publicarBloque(bloque(i));   // purga 0..1

        JobActivoResponse body = controllerCon(jobs).jobActivo().getBody();
        assertEquals(5, body.getTotalBloques());                 // el front arranca en desde=5
        assertEquals(2, body.getPrimerBloqueDisponible());
        assertNotNull(body.getTemporizadorInicioUtc());
        assertNotNull(body.getDuracionRealMs());
        assertTrue(body.getDuracionRealMs() >= 0);
    }

    // ------------------------------------------------ /bloques con desde purgado (bug del mapa)

    @Test
    void desdePurgadoDevuelveVacioConPrimerBloqueDisponible() {
        RegistroJobs jobs = new RegistroJobs();
        ConsultaJobsController controller = controllerCon(jobs);
        EstadoJob job = jobs.crear("2", 5);
        job.setMaxBloquesConAsignaciones(3);
        for (int i = 0; i < 5; i++) job.publicarBloque(bloque(i));   // retiene 2..4

        Map<String, Object> body = controller.bloquesJob(job.getJobId(), 0).getBody();
        assertTrue(((List<?>) body.get("bloques")).isEmpty(), "desde purgado: sin bloques viejos");
        assertEquals(5, body.get("total"));
        assertEquals(2, body.get("primerBloqueDisponible"));
    }

    @Test
    void desdeValidoConservaElComportamientoActual() {
        RegistroJobs jobs = new RegistroJobs();
        ConsultaJobsController controller = controllerCon(jobs);
        EstadoJob job = jobs.crear("2", 5);
        job.setMaxBloquesConAsignaciones(3);
        for (int i = 0; i < 5; i++) job.publicarBloque(bloque(i));

        Map<String, Object> body = controller.bloquesJob(job.getJobId(), 3).getBody();
        @SuppressWarnings("unchecked")
        List<BloqueSimulacion> bloques = (List<BloqueSimulacion>) body.get("bloques");
        assertEquals(2, bloques.size());
        assertEquals(3, bloques.get(0).getBloqueIdx());
        assertEquals(4, bloques.get(1).getBloqueIdx());

        // desde = total (patrón de polling normal): vacío, sin regresión.
        Map<String, Object> punta = controller.bloquesJob(job.getJobId(), 5).getBody();
        assertTrue(((List<?>) punta.get("bloques")).isEmpty());
    }

    // ------------------------------------------------ /almacenes/serie con desde purgado

    @Test
    void seriePurgadaDevuelveVacioConPrimeraSerieDisponible() {
        RegistroJobs jobs = new RegistroJobs();
        ConsultaJobsController controller = controllerCon(jobs);
        EstadoJob job = jobs.crear("2", 5);
        job.setMaxBloquesConAsignaciones(3);
        for (int i = 0; i < 5; i++) job.publicarSerieAlmacenes(List.of(slot("SKBO", 100 + i)));

        SerieAlmacenesResponse body = controller.serieAlmacenesJob(job.getJobId(), 0).getBody();
        assertTrue(body.getSeries().isEmpty(), "desde purgado: sin series viejas mal etiquetadas");
        assertEquals(5, body.getTotal());
        assertEquals(2, body.getPrimeraSerieDisponible());

        // desde válido: etiquetas bloqueIdx absolutas correctas.
        SerieAlmacenesResponse desde3 = controller.serieAlmacenesJob(job.getJobId(), 3).getBody();
        assertEquals(2, desde3.getSeries().size());
        assertEquals(3, desde3.getSeries().get(0).getBloqueIdx());
        assertEquals(4, desde3.getSeries().get(1).getBloqueIdx());
    }

    // ------------------------------------------------ temporizador real compartido

    @Test
    void temporizadorArrancaConElPrimerBloqueYSeCongelaAlTerminar() {
        RegistroJobs jobs = new RegistroJobs();
        EstadoJob job = jobs.crear("2", 5);

        assertNull(job.getDuracionRealMs(), "sin bloques aún no hay temporizador");
        assertNull(job.primerBloqueRealMs);

        job.publicarBloque(bloque(0));
        Long arranque = job.primerBloqueRealMs;
        assertNotNull(arranque);
        assertTrue(job.getDuracionRealMs() >= 0);

        job.publicarBloque(bloque(1));
        assertEquals(arranque, job.primerBloqueRealMs, "el arranque se fija una sola vez");

        job.finRealMs = arranque + 1234;   // como lo fija RegistroJobs.ejecutar al terminar
        assertEquals(1234L, job.getDuracionRealMs());
        assertEquals(1234L, job.getDuracionRealMs(), "congelado: ya no crece");
    }

    @Test
    void estadoJobExponeTemporizadorYDuracion() {
        RegistroJobs jobs = new RegistroJobs();
        ConsultaJobsController controller = controllerCon(jobs);
        EstadoJob job = jobs.crear("2", 5);

        EstadoJobResponse antes = controller.estadoJob(job.getJobId()).getBody();
        assertNull(antes.getTemporizadorInicioUtc());
        assertNull(antes.getDuracionRealMs());

        job.publicarBloque(bloque(0));
        EstadoJobResponse despues = controller.estadoJob(job.getJobId()).getBody();
        assertNotNull(despues.getTemporizadorInicioUtc());
        assertNotNull(despues.getDuracionRealMs());
        assertTrue(despues.getDuracionRealMs() >= 0);
    }

    // ----------------------------------------------------------------------- helpers

    private static ConsultaJobsController controllerCon(RegistroJobs jobs) {
        PlanificadorService service = new PlanificadorService(null, null, null, jobs,
                null, null);
        ConsultaJobsService jobQuery = new ConsultaJobsService(jobs, null);
        return new ConsultaJobsController(service, jobQuery);
    }

    private static BloqueSimulacion bloque(int idx) {
        BloqueSimulacion b = new BloqueSimulacion();
        b.setBloqueIdx(idx);
        b.setHoraInicio("2026-01-02T0" + idx + ":00");
        b.setHoraFin("2026-01-02T0" + (idx + 1) + ":00");
        return b;
    }

    private static OcupacionAlmacenSlot slot(String aeropuerto, int ocupacion) {
        OcupacionAlmacenSlot s = new OcupacionAlmacenSlot();
        s.setAeropuerto(aeropuerto);
        s.setHora("2026-01-02T13:00");
        s.setOcupacion(ocupacion);
        s.setCapacidadMaxima(430);
        return s;
    }
}
