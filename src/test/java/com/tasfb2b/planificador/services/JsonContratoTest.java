package com.tasfb2b.planificador.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasfb2b.planificador.dto.DashboardResponse;
import com.tasfb2b.planificador.dto.EstadoJobResponse;
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
        JobsRegistry jobs = new JobsRegistry();
        PlanificadorService service = serviceCon(jobs);
        JobState job = jobs.crear("2", 14);   // recién encolado: sin fechaInicio/fin/error/alerta

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
        JobsRegistry jobs = new JobsRegistry();
        JobQueryService jobQuery = jobQueryCon(jobs);
        JobState job = jobs.crear("2", 14);

        String json = mapper.writeValueAsString(jobQuery.getDashboardJob(job.getJobId()));

        assertTrue(json.contains("\"ultimoBloque\":null"), "ultimoBloque se emite siempre, incluso null");
        assertFalse(json.contains("\"fechaInicio\""), "fechaInicio null debe omitirse");
        assertTrue(json.contains("\"metricas\""));
        assertTrue(json.contains("\"tasas\""));
    }

    // ----------------------------------------------------------------------- helpers

    private static PlanificadorService serviceCon(JobsRegistry jobs) {
        return new PlanificadorService(null, null, null, jobs, null, null, null);
    }

    private static JobQueryService jobQueryCon(JobsRegistry jobs) {
        // getDashboardJob solo usa el registry; DataLoader no interviene → null.
        return new JobQueryService(jobs, null);
    }
}
