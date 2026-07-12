package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.ConsultaJobsService;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasfb2b.planificador.dto.datos.AeropuertoDTO;
import com.tasfb2b.planificador.dto.jobs.TableroResponse;
import com.tasfb2b.planificador.dto.jobs.EstadoJobResponse;
import com.tasfb2b.planificador.dto.simulacion.AsignacionMaleta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica la FORMA del JSON serializado (Tanda 1B) en los puntos sensibles a byte-compatibilidad
 * con el contrato del front: los campos opcionales (fechaInicio/fin/error/alertaColapso) se OMITEN
 * cuando son {@code null} ({@code @JsonInclude(NON_NULL)}), mientras que {@code ultimoBloque} y los
 * conteos/listas obligatorios se emiten SIEMPRE (incluso {@code null} o vacíos). Así se replica el
 * comportamiento del mapa {@code LinkedHashMap} anterior, que solo ponía las claves opcionales si no
 * eran null.
 */
class JsonContratoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void estadoOmiteOpcionalesNullYConservaSiempreSeedYVuelosCancelados() throws Exception {
        RegistroJobs jobs = new RegistroJobs();
        PlanificadorService service = serviceCon(jobs);
        EstadoJob job = jobs.crear("2", 14);   // recién encolado: sin fechaInicio/fin/error/alerta

        String json = mapper.writeValueAsString(service.getEstadoJob(job.getJobId()));

        // Opcionales null: ausentes (igual que el mapa, que no los ponía).
        assertFalse(json.contains("\"fechaInicio\""), "fechaInicio null debe omitirse");
        assertFalse(json.contains("\"fin\""), "fin null debe omitirse");
        assertFalse(json.contains("\"error\""), "error null debe omitirse");
        assertFalse(json.contains("\"alertaColapso\""), "alertaColapso null debe omitirse");
        // Siempre presentes.
        assertTrue(json.contains("\"seed\""), "seed (long) está siempre");
        assertTrue(json.contains("\"posicionEnCola\""), "posicionEnCola está siempre");
        assertTrue(json.contains("\"vuelosCancelados\":[]"), "vuelosCancelados está siempre (vacío)");
    }

    @Test
    void dashboardEmiteUltimoBloqueNullYOmiteFechaInicio() throws Exception {
        RegistroJobs jobs = new RegistroJobs();
        ConsultaJobsService jobQuery = jobQueryCon(jobs);
        EstadoJob job = jobs.crear("2", 14);

        String json = mapper.writeValueAsString(jobQuery.getTableroJob(job.getJobId()));

        assertTrue(json.contains("\"ultimoBloque\":null"), "ultimoBloque se emite siempre, incluso null");
        assertFalse(json.contains("\"fechaInicio\""), "fechaInicio null debe omitirse");
        assertTrue(json.contains("\"metricas\""));
        assertTrue(json.contains("\"tasas\""));
    }

    /**
     * {@code GET /aeropuertos}: cada {@link AeropuertoDTO} debe emitir {@code gmt} (offset horario que
     * el front usa para el reloj local y la conversión local→UTC). El dataset trae husos enteros, así
     * que se serializa como número con signo (p. ej. {@code -5.0}).
     */
    @Test
    void aeropuertoEmiteGmt() throws Exception {
        AeropuertoDTO dto = new AeropuertoDTO();
        dto.setCodigo("SPIM");
        dto.setLatitud(-12.02);
        dto.setLongitud(-77.11);
        dto.setCapacidadAlmacen(440);
        dto.setGmt(-5.0);

        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"gmt\":-5.0"), "gmt debe serializarse con su valor y signo");
        assertTrue(json.contains("\"capacidadAlmacen\":440"), "los campos previos del DTO se conservan");
    }

    /**
     * Fragmentación: un envío NO fragmentado debe serializar byte-idéntico a hoy — los tres campos
     * nuevos ({@code idEnvioPadre}/{@code fragmento}/{@code totalFragmentos}) son {@code @JsonInclude
     * (NON_NULL)} campo a campo y quedan omitidos cuando son null.
     */
    @Test
    void asignacionNoFragmentadaOmiteLosCamposDeFragmentacion() throws Exception {
        AsignacionMaleta a = new AsignacionMaleta();
        a.setBatchId("SKBO-000000001");
        a.setOrigen("SKBO");
        a.setDestino("SEQM");
        a.setCantidad(3);

        String json = mapper.writeValueAsString(a);

        assertFalse(json.contains("idEnvioPadre"), "idEnvioPadre null se omite");
        assertFalse(json.contains("fragmento"), "fragmento null se omite");
        assertFalse(json.contains("totalFragmentos"), "totalFragmentos null se omite");
        assertTrue(json.contains("\"batchId\":\"SKBO-000000001\""), "los campos previos se conservan");
    }

    @Test
    void asignacionFragmentadaEmiteLosCamposDeFragmentacion() throws Exception {
        AsignacionMaleta a = new AsignacionMaleta();
        a.setBatchId("SKBO-000000001-F2");
        a.setCantidad(334);
        a.setIdEnvioPadre("SKBO-000000001");
        a.setFragmento(2);
        a.setTotalFragmentos(3);

        String json = mapper.writeValueAsString(a);

        assertTrue(json.contains("\"idEnvioPadre\":\"SKBO-000000001\""));
        assertTrue(json.contains("\"fragmento\":2"));
        assertTrue(json.contains("\"totalFragmentos\":3"));
    }

    // ----------------------------------------------------------------------- helpers

    private static PlanificadorService serviceCon(RegistroJobs jobs) {
        return new PlanificadorService(null, null, null, jobs, null, null);
    }

    private static ConsultaJobsService jobQueryCon(RegistroJobs jobs) {
        // getTableroJob solo usa el registry; CargadorDatos no interviene → null.
        return new ConsultaJobsService(jobs, null);
    }
}
