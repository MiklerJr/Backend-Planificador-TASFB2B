package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Node;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.util.DataLoader;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifica que {@link PlanificadorService#buildAsignaciones} expone los tiempos UTC
 * que ya entrega el motor (AlgorithmMapper normaliza con el offset de cada aeropuerto)
 * y reconstruye la hora de pared local de cada extremo sumando ese offset
 * (local = utc + offset), tanto en los tramos como en el nacimiento del envío.
 */
class PlanificadorAsignacionesUtcTest {

    // OPKC=Karachi (+5), EDDI=Berlín (+2), SCEL=Santiago (-3).
    private PlanificadorService serviceConAeropuertos() {
        DataLoader dataLoader = new DataLoader(null, null, null) {
            @Override
            public List<Aeropuerto> getAeropuertos() {
                return List.of(
                        aeropuerto("OPKC", 5),
                        aeropuerto("EDDI", 2),
                        aeropuerto("SCEL", -3));
            }
        };
        return new PlanificadorService(dataLoader, null, null, new JobsRegistry(),
                null, null, null, null, null);
    }

    @Test
    void localSeReconstruyeSumandoElOffsetDeCadaAeropuerto() {
        PlanificadorService service = serviceConAeropuertos();

        // El motor ya entrega UTC: el envío de Karachi (00:52 local) tiene readyTime 19:52 UTC.
        LocalDateTime readyUtc = LocalDateTime.of(2026, 1, 1, 19, 52);
        LuggageBatch batch = new LuggageBatch("E1", 10, 48, "OPKC", "EDDI", readyUtc);

        // Tramo OPKC→EDDI: salida 19:52 UTC, duración real 300 min → llegada 00:52 UTC.
        Edge edge = edge("V1", "OPKC", "EDDI", 300);
        batch.setAssignedRoute(List.of(edge));
        batch.setAssignedDepartures(List.of(epochMin(readyUtc)));

        SimulacionResponse.AsignacionMaleta asig = service.buildAsignaciones(List.of(batch)).get(0);

        assertEquals("2026-01-01T19:52", asig.getRegistroUtc());
        assertEquals("2026-01-02T00:52", asig.getRegistroLocal());  // +5h (Karachi)

        SimulacionResponse.TramoRuta tramo = asig.getTramos().get(0);
        assertEquals("2026-01-01T19:52", tramo.getSalidaUtc());
        assertEquals("2026-01-02T00:52", tramo.getSalidaLocal());   // +5h origen
        assertEquals("2026-01-02T00:52", tramo.getLlegadaUtc());
        assertEquals("2026-01-02T02:52", tramo.getLlegadaLocal());  // +2h destino
        assertEquals(300, tramo.getDuracionMin());                  // duración real, no la resta de *Local
    }

    @Test
    void rangoUtcDelBloqueUsaMinMaxDeRegistroUtcNoElScStartLocal() {
        // Bloque con dos envíos: uno de Karachi (registroUtc 1-ene) y uno local (2-ene). El rango
        // UTC del bloque debe arrancar el 1-ene, aunque el horaInicio local del bloque sea el 2-ene.
        SimulacionResponse.AsignacionMaleta a1 = new SimulacionResponse.AsignacionMaleta();
        a1.setRegistroUtc("2026-01-01T19:52");
        SimulacionResponse.AsignacionMaleta a2 = new SimulacionResponse.AsignacionMaleta();
        a2.setRegistroUtc("2026-01-02T00:02");
        SimulacionResponse.AsignacionMaleta sinReg = new SimulacionResponse.AsignacionMaleta(); // null → se ignora

        String[] rango = PlanificadorService.rangoUtcRegistros(List.of(a1, a2, sinReg));
        assertEquals("2026-01-01T19:52", rango[0]);
        assertEquals("2026-01-02T00:02", rango[1]);

        assertEquals(null, PlanificadorService.rangoUtcRegistros(List.of(sinReg))[0]);
    }

    @Test
    void tramoQueCruzaMedianocheUtcYDestinoConOffsetNegativo() {
        PlanificadorService service = serviceConAeropuertos();

        // Caso del primer vuelo del dataset: OPKC→SCEL, salida 19:04 UTC (= 00:04 Karachi),
        // duración real 1187 min → llegada 14:51 UTC (= 11:51 Santiago).
        LocalDateTime salidaUtc = LocalDateTime.of(2025, 12, 31, 19, 4);
        LuggageBatch batch = new LuggageBatch("E2", 10, 48, "OPKC", "SCEL", salidaUtc);

        Edge edge = edge("V2", "OPKC", "SCEL", 1187);
        batch.setAssignedRoute(List.of(edge));
        batch.setAssignedDepartures(List.of(epochMin(salidaUtc)));

        SimulacionResponse.TramoRuta tramo = service.buildAsignaciones(List.of(batch)).get(0).getTramos().get(0);

        assertEquals("2025-12-31T19:04", tramo.getSalidaUtc());
        assertEquals("2026-01-01T00:04", tramo.getSalidaLocal());   // +5h (Karachi)
        assertEquals("2026-01-01T14:51", tramo.getLlegadaUtc());
        assertEquals("2026-01-01T11:51", tramo.getLlegadaLocal());  // -3h (Santiago)
        assertEquals(1187, tramo.getDuracionMin());
    }

    private static long epochMin(LocalDateTime dt) {
        return dt.toLocalDate().toEpochDay() * 1440L + dt.getHour() * 60L + dt.getMinute();
    }

    private static Edge edge(String id, String from, String to, int durationMinutes) {
        Edge e = new Edge();
        e.id = id;
        e.from = new Node(from);
        e.to = new Node(to);
        e.durationMinutes = durationMinutes;
        return e;
    }

    private static Aeropuerto aeropuerto(String codigo, int offset) {
        Aeropuerto a = new Aeropuerto();
        a.setCodigo(codigo);
        a.setOffsetHorario(offset);
        return a;
    }
}
