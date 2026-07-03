package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.trabajos.EstadoTrabajo;
import com.tasfb2b.planificador.servicios.trabajos.ConsultaTrabajosService;
import com.tasfb2b.planificador.servicios.trabajos.RegistroTrabajos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasfb2b.planificador.dto.datos.AeropuertoDTO;
import com.tasfb2b.planificador.dto.trabajos.TableroResponse;
import com.tasfb2b.planificador.dto.trabajos.EstadoTrabajoResponse;
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
        RegistroTrabajos jobs = new RegistroTrabajos();
        PlanificadorService service = serviceCon(jobs);
        EstadoTrabajo job = jobs.crear("2", 14);   // recién encolado: sin fechaInicio/fin/error/alerta

        String json = mapper.writeValueAsString(service.getEstadoTrabajo(job.getJobId()));

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
        RegistroTrabajos jobs = new RegistroTrabajos();
        ConsultaTrabajosService jobQuery = jobQueryCon(jobs);
        EstadoTrabajo job = jobs.crear("2", 14);

        String json = mapper.writeValueAsString(jobQuery.getTableroTrabajo(job.getJobId()));

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

    // ----------------------------------------------------------------------- helpers

    private static PlanificadorService serviceCon(RegistroTrabajos jobs) {
        return new PlanificadorService(null, null, null, jobs, null, null);
    }

    private static ConsultaTrabajosService jobQueryCon(RegistroTrabajos jobs) {
        // getTableroTrabajo solo usa el registry; CargadorDatos no interviene → null.
        return new ConsultaTrabajosService(jobs, null);
    }
}
