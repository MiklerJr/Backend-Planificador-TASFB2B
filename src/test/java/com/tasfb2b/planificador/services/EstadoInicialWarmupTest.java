package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verificación del snapshot de ESTADO INICIAL (fechaInicio + warm-up en E1/E3): de las
 * asignaciones pre-calculadas durante el warm-up, {@code construirEstadoInicial} debe quedarse
 * solo con los envíos AÚN ACTIVOS al reloj UTC del fin del warm-up (max readyTime, mismo
 * criterio que maletasEntregadasAcum):
 * <ul>
 *   <li>entregado antes del reloj → fuera (ya aterrizó, no hay nada que pintar);</li>
 *   <li>en vuelo al reloj → dentro (el avión está en el aire en fechaInicio);</li>
 *   <li>con tramos aún por salir → dentro (espera en origen/escala su vuelo futuro);</li>
 *   <li>sinRuta → fuera (sigue vivo vía backlog y reaparecerá en los bloques visibles).</li>
 * </ul>
 */
class EstadoInicialWarmupTest {

    private static final LocalDate DIA = LocalDate.of(2026, 1, 1);

    @Test
    void incluyeSoloLosEnviosActivosAlRelojDelFinDelWarmup() {
        Map<String, LuggageBatch> auditWarmup = new LinkedHashMap<>();
        // D es el registro más reciente del warm-up: fija el reloj UTC en 12:00.
        auditWarmup.put("D", batchSinRuta("D", LocalDateTime.of(DIA, LocalTime.of(12, 0))));
        // A: aterrizó a las 09:00 (antes del reloj) → entregado, fuera del snapshot.
        auditWarmup.put("A", batchConVuelo("A", LocalTime.of(7, 0), LocalTime.of(8, 0), 60));
        // B: despegó 11:30 y aterriza 13:00 → EN EL AIRE a las 12:00, dentro.
        auditWarmup.put("B", batchConVuelo("B", LocalTime.of(7, 30), LocalTime.of(11, 30), 90));
        // E: su vuelo sale a las 14:00 (futuro) → espera en origen con ruta asignada, dentro.
        auditWarmup.put("E", batchConVuelo("E", LocalTime.of(9, 0), LocalTime.of(14, 0), 60));
        // C: sin ruta → fuera (vuelve por el backlog en la fase visible).
        auditWarmup.put("C", batchSinRuta("C", LocalDateTime.of(DIA, LocalTime.of(8, 0))));

        PlanificadorService service =
                new PlanificadorService(null, null, null, null, null, null, null, null, null);
        List<SimulacionResponse.AsignacionMaleta> snapshot = service.construirEstadoInicial(auditWarmup);

        assertEquals(2, snapshot.size(), "solo B (en el aire) y E (tramo futuro) siguen activos");
        List<String> ids = snapshot.stream().map(SimulacionResponse.AsignacionMaleta::getBatchId).toList();
        assertTrue(ids.contains("B") && ids.contains("E"), "ids esperados: B y E, recibidos " + ids);

        // El snapshot trae los tramos UTC completos: el front interpola igual que con los bloques.
        SimulacionResponse.AsignacionMaleta enElAire = snapshot.stream()
                .filter(a -> a.getBatchId().equals("B")).findFirst().orElseThrow();
        assertTrue(enElAire.isEnrutada());
        assertEquals(1, enElAire.getTramos().size());
        assertFalse(enElAire.getTramos().get(0).getSalidaUtc().isEmpty());
        assertFalse(enElAire.getTramos().get(0).getLlegadaUtc().isEmpty());
    }

    @Test
    void sinWarmupDevuelveListaVacia() {
        PlanificadorService service =
                new PlanificadorService(null, null, null, null, null, null, null, null, null);
        assertTrue(service.construirEstadoInicial(new LinkedHashMap<>()).isEmpty());
        assertTrue(service.construirEstadoInicial(null).isEmpty());
    }

    // ----------------------------------------------------------------------- helpers

    /** Batch de 10 maletas con un tramo único que despega a {@code salida} y dura {@code durMin}. */
    private static LuggageBatch batchConVuelo(String id, LocalTime ready, LocalTime salida, int durMin) {
        Edge tramo = new Edge();
        tramo.idx = 0;
        tramo.id = "F-" + id;
        tramo.durationMinutes = durMin;

        LuggageBatch b = new LuggageBatch(id, 10, 24, "AAA", "BBB", LocalDateTime.of(DIA, ready));
        b.setAssignedRoute(List.of(tramo));
        b.setAssignedDepartures(List.of(
                GreedyRepairOperator.toEpochMinPublic(LocalDateTime.of(DIA, salida))));
        b.setCumpleSLA(true);
        return b;
    }

    private static LuggageBatch batchSinRuta(String id, LocalDateTime ready) {
        return new LuggageBatch(id, 5, 24, "AAA", "BBB", ready);
    }
}
