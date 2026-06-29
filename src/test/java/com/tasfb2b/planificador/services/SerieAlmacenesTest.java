package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator.RouteCandidate;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.dto.almacenes.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verificación de la serie de ocupación por SLOT de 60 min ({@code buildSerieAlmacenes}) y del
 * REALISMO del bloque al publicarse: la serie debe reflejar EXACTAMENTE lo que el modelo interno
 * cobró slot a slot — espera en origen {@code [registro, primer vuelo)}, estadía de escala
 * {@code [llegada, salida siguiente)}, destino {@code [llegada, +10 min)} y la espera en origen
 * de envíos sin ruta del backlog — sin agregaciones que pierdan granularidad (el DTO diario
 * {@code OcupacionAlmacen} colapsa todo a un pico por día; la serie no).
 */
class SerieAlmacenesTest {

    private static final int CAPACIDAD_VUELO = 50;
    private static final int CAPACIDAD_ALMACEN = 500;
    private static final LocalDate DIA = LocalDate.of(2026, 1, 1);

    /**
     * B1 (20 maletas, ready 07:00) vuela AAA→BBB (08:30-09:30) y BBB→CCC (18:00-19:00):
     * espera en origen AAA [07:00, 08:30) → slots 07:00 y 08:00;
     * escala en BBB [09:30, 18:00) → slots 09:00 … 17:00 (9 slots);
     * destino CCC [19:00, 19:10) → slot 19:00.
     */
    @Test
    void laSerieReproduceSlotASlotLaEstadiaCompletaQueCobroElModelo() {
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        enrutar(op, batch("B1", 20, LocalTime.of(7, 0)), blockFlight, blockAirport);
        op.commitBlock(blockFlight, blockAirport);

        PlanificadorService service = serviceSinDataset();
        List<OcupacionAlmacenSlot> serie =
                service.buildSerieAlmacenes(blockAirport, graph, op);

        assertEquals(12, serie.size(), "2 slots de origen + 9 de escala + 1 de destino");
        assertEquals(2, slotsDe(serie, "AAA").size(), "origen: [07:00, 08:30) → slots 07 y 08");
        assertEquals(9, slotsDe(serie, "BBB").size(), "escala: [09:30, 18:00) → slots 09..17");
        assertEquals(1, slotsDe(serie, "CCC").size(), "destino: [19:00, 19:10) → slot 19");
        assertTrue(serie.stream().anyMatch(s ->
                        s.getAeropuerto().equals("BBB") && s.getHora().startsWith("2026-01-01T09:00")),
                "las horas son el inicio del slot en eje UTC");

        // Realismo: cada slot del DTO coincide EXACTAMENTE con la ocupación global del modelo.
        for (OcupacionAlmacenSlot s : serie) {
            assertEquals(20, s.getOcupacion(), "B1 ocupa 20 maletas en " + s.getAeropuerto() + "@" + s.getHora());
            long slotMin = GreedyRepairOperator.toEpochMinPublic(LocalDateTime.parse(s.getHora()));
            long slotKey = GreedyRepairOperator.claveAlmacenDeSlot(
                    graph.nodes.get(s.getAeropuerto()).idx, slotMin);
            assertEquals(op.ocupacionGlobalAlmacen(slotKey), s.getOcupacion(),
                    "el DTO reporta lo mismo que valida el motor (slot " + s.getHora() + ")");
            assertEquals("VERDE", s.getSemaforo(), "20/500 = 4% ⇒ VERDE");
        }
    }

    /** La espera en ORIGEN de un envío sin ruta (backlog) también aparece en la serie. */
    @Test
    void laSerieIncluyeLaEsperaEnOrigenDeEnviosSinRutaDelBacklog() {
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        enrutar(op, batch("B1", 20, LocalTime.of(7, 0)), blockFlight, blockAirport);
        op.commitBlock(blockFlight, blockAirport);

        // B2 (5 maletas, ready 07:30) queda SIN ruta; el reloj UTC avanza a 10:00 con B3.
        LuggageBatch b2 = batch("B2", 5, LocalTime.of(7, 30));
        LuggageBatch b3 = batch("B3", 1, LocalTime.of(10, 0));
        op.reconstruirEsperaOrigenBacklog(List.of(b2), List.of(b3));

        PlanificadorService service = serviceSinDataset();
        List<OcupacionAlmacenSlot> serie =
                service.buildSerieAlmacenes(blockAirport, graph, op);

        // El slot 08:00 de AAA fue tocado por B1; su ocupación acumulada debe incluir a B2
        // (espera [07:30, 10:00) del backlog): 20 + 5 = 25.
        OcupacionAlmacenSlot slot8 = serie.stream()
                .filter(s -> s.getAeropuerto().equals("AAA") && s.getHora().startsWith("2026-01-01T08:00"))
                .findFirst().orElseThrow();
        assertEquals(25, slot8.getOcupacion(),
                "la serie incluye la espera en origen del backlog (20 de B1 + 5 de B2)");
    }

    // ----------------------------------------------------------------------- helpers

    private static PlanificadorService serviceSinDataset() {
        return new PlanificadorService(null, null, null, null, null, null);
    }

    private static List<OcupacionAlmacenSlot> slotsDe(
            List<OcupacionAlmacenSlot> serie, String aeropuerto) {
        return serie.stream().filter(s -> s.getAeropuerto().equals(aeropuerto)).toList();
    }

    private static void enrutar(GreedyRepairOperator op, LuggageBatch b,
                                Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        RouteCandidate ruta = op.generarCandidatosRuta(b, blockFlight, blockAirport, 3).stream()
                .filter(RouteCandidate::isCumpleSLA)
                .findFirst().orElseThrow();
        op.aplicarCandidatoRuta(b, ruta);
        op.aplicarCandidatoBloque(b, ruta, blockFlight, blockAirport);
    }

    private static LuggageBatch batch(String id, int qty, LocalTime ready) {
        return new LuggageBatch(id, qty, 24, "AAA", "CCC", LocalDateTime.of(DIA, ready));
    }

    /** AAA→BBB (08:30-09:30) y BBB→CCC (18:00-19:00): escala de 8h30 en BBB, sin ruta directa. */
    private static Graph grafoEscalaLarga() {
        Graph g = new Graph();
        Node aaa = node("AAA"), bbb = node("BBB"), ccc = node("CCC");
        g.nodes.put("AAA", aaa);
        g.nodes.put("BBB", bbb);
        g.nodes.put("CCC", ccc);
        addEdge(g, 0, aaa, bbb, "F1", "08:30", "09:30");
        addEdge(g, 1, bbb, ccc, "F2", "18:00", "19:00");
        return g;
    }

    private static Node node(String code) {
        Node n = new Node(code);
        n.capacity = CAPACIDAD_ALMACEN;
        return n;
    }

    private static void addEdge(Graph g, int idx, Node from, Node to, String id, String dep, String arr) {
        Edge e = new Edge();
        e.idx = idx;
        e.id = id;
        e.from = from;
        e.to = to;
        e.capacity = CAPACIDAD_VUELO;
        e.departureTime = LocalDateTime.of(DIA, LocalTime.parse(dep));
        e.arrivalTime = LocalDateTime.of(DIA, LocalTime.parse(arr));
        e.departureLocalTime = e.departureTime.toLocalTime();
        e.depMinuteOfDay = e.departureLocalTime.getHour() * 60 + e.departureLocalTime.getMinute();
        e.durationMinutes = (int) Duration.between(e.departureTime, e.arrivalTime).toMinutes();
        e.cost = e.durationMinutes;
        g.addEdge(e);
    }
}
