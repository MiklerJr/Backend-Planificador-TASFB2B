package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.AcoBlockEngine;
import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator.RouteCandidate;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase R — la capacidad de almacén es OCUPACIÓN CONCURRENTE: la estadía COMPLETA
 * {@code [llegada, salida)} de cada pierna debe caber, no solo el slot de llegada. Estos tests
 * fijan el invariante en los tres caminos que confirman rutas: Dijkstra hijo
 * ({@code generarCandidatosRuta}), materialización de caché ({@code materializarRutaCandidata})
 * y el {@code repair()} del ALNS, más el motor ACO end-to-end. Grafo con escala LARGA en BBB
 * (llega 09:30, sale 18:00): un slot INTERMEDIO lleno (13:00, distinto del de llegada) debe
 * rechazar la ruta — antes solo se validaba el slot de llegada y los intermedios se cobraban
 * sin chequear (overflow del almacén).
 */
class GreedyRepairOperatorEstadiaCompletaTest {

    private static final int CAPACIDAD_ALMACEN = 500;

    @Test
    void dijkstraHijoRechazaRutaSiUnSlotIntermedioDeLaEscalaEstaLleno() {
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        LuggageBatch batch = batch("B1", 20, 24);

        // Sanidad: sin ocupación, la ruta del día 1 existe y es on-time.
        List<RouteCandidate> libres = op.generarCandidatosRuta(batch, new HashMap<>(), new HashMap<>(), 3);
        assertTrue(libres.stream().anyMatch(RouteCandidate::isCumpleSLA),
                "sin ocupación la ruta vía BBB del día 1 es on-time");

        // Slot intermedio (13:00) de la estadía en BBB lleno; el de llegada (09:30) queda libre.
        Map<Long, Integer> blockAirport = slotIntermedioLleno(graph);
        List<RouteCandidate> candidatos = op.generarCandidatosRuta(batch, new HashMap<>(), blockAirport, 3);

        assertTrue(candidatos.stream().noneMatch(RouteCandidate::isCumpleSLA),
                "con un slot intermedio de la estadía lleno, la ruta del día 1 no debe ofrecerse");
    }

    @Test
    void materializarRutaCandidataRechazaSiUnSlotIntermedioDeLaEscalaEstaLleno() {
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        LuggageBatch batch = batch("B1", 20, 24);

        List<Edge> rutaViaBbb = List.of(graph.edges.get(0), graph.edges.get(1));
        assertTrue(op.materializarRutaCandidata(batch, rutaViaBbb, new HashMap<>(), new HashMap<>())
                        .isCumpleSLA(),
                "sin ocupación, el esqueleto cacheado materializa on-time");

        assertNull(op.materializarRutaCandidata(batch, rutaViaBbb, new HashMap<>(), slotIntermedioLleno(graph)),
                "la materialización debe revalidar la estadía completa, no solo el slot de llegada");
    }

    @Test
    void repairNoCobraSlotsDeEstadiaNuncaValidados() {
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        LuggageBatch batch = batch("B1", 20, 24);

        // El slot intermedio queda a 10 maletas del tope: el batch (20) NO cabe en él.
        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveSlotIntermedio(graph), CAPACIDAD_ALMACEN - 10);
        Map<Long, Integer> blockFlight = new HashMap<>();

        op.repair(new AlnsSolution(List.of(batch)), List.of(batch), blockFlight, blockAirport);

        Node bbb = graph.nodes.get("BBB");
        for (Map.Entry<Long, Integer> e : blockAirport.entrySet()) {
            assertTrue(e.getValue() <= bbb.capacity,
                    "ningún slot de almacén queda sobre capacidad tras repair: "
                            + e.getValue() + "/" + bbb.capacity);
        }
        if (batch.getAssignedRoute() != null && !batch.getAssignedRoute().isEmpty()) {
            long primeraSalida = batch.getAssignedDepartures().get(0);
            long readyMin = GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime());
            assertTrue(primeraSalida - readyMin > 24 * 60,
                    "si enruta, debe ser en un día posterior (la estadía del día 1 no cabe)");
        }
    }

    @Test
    void acoPadreNoSobrepasaCapacidadDeAlmacenEnSlotsIntermedios() {
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        AcoBlockEngine engine = new AcoBlockEngine(new PlanificadorProperties());
        LuggageBatch batch = batch("B1", 20, 24);

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveSlotIntermedio(graph), CAPACIDAD_ALMACEN - 10);

        engine.procesar(graph, op, List.of(batch), blockFlight, blockAirport, new Random(7L), 1_000L);

        Node bbb = graph.nodes.get("BBB");
        for (Map.Entry<Long, Integer> e : blockAirport.entrySet()) {
            assertTrue(e.getValue() <= bbb.capacity,
                    "ningún slot de almacén queda sobre capacidad tras el ACO: "
                            + e.getValue() + "/" + bbb.capacity);
        }
        // Día 1 no cabe (slot intermedio) y el día 2 es tardío (F1 no confirma tardías):
        // el envío queda sinRuta, jamás cobrado sobre un slot lleno.
        assertFalse(batch.isCumpleSLA());
        assertTrue(batch.getAssignedRoute() == null || batch.getAssignedRoute().isEmpty());
    }

    @Test
    void sinRutaPorAlmacenLlenoClasificaElDesbordeDeEstadiaComoColapso() {
        // Señal de COLAPSO que detiene la simulación (PlanificadorService 3b): un envío que no
        // logra ruta on-time respetando almacenes pero SÍ la tendría ignorándolos = le llegaron
        // maletas a un almacén que quedaría en sobrecapacidad. Debe dispararse también cuando lo
        // que bloquea es un slot INTERMEDIO de la estadía, no solo el de llegada.
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        LuggageBatch batch = batch("B1", 20, 24);

        assertFalse(op.sinRutaPorAlmacenLleno(batch),
                "con almacenes libres no hay colapso: la ruta on-time existe");

        // Slot intermedio de la estadía en BBB lleno, commiteado a la ocupación GLOBAL
        // (sinRutaPorAlmacenLleno lee el estado global post-commit).
        op.commitBlock(new HashMap<>(), slotIntermedioLleno(graph));

        assertTrue(op.sinRutaPorAlmacenLleno(batch),
                "el desborde de estadía debe clasificarse como colapso por almacén lleno");
    }

    @Test
    void evaluarPreColapsoReportaDesbordeDuroSobreElCienPorCiento() {
        // Señal del freno DURO (PlanificadorService): utilización > 1.0 en un slot tocado por el
        // bloque ⇒ ocupación real sobre capacidad ⇒ la simulación se detiene de inmediato.
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);

        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveSlotIntermedio(graph), CAPACIDAD_ALMACEN + 50);
        op.commitBlock(new HashMap<>(), blockAirport);

        GreedyRepairOperator.PreColapso pre = op.evaluarPreColapso(blockAirport, List.of());

        assertTrue(pre.utilAlmacenMax() > 1.0,
                "una ocupación sobre capacidad debe reportar utilización > 100%");
        assertEquals("BBB", pre.almacenCritico());
    }

    // ----------------------------------------------------------------------- helpers

    /** Slot de las 13:00 del día 1 en BBB: dentro de la estadía [09:30, 18:00) pero NO el de llegada. */
    private static long claveSlotIntermedio(Graph graph) {
        long epochMin = GreedyRepairOperator.toEpochMinPublic(
                LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(13, 0)));
        return GreedyRepairOperator.claveAlmacenDeSlot(graph.nodes.get("BBB").idx, epochMin);
    }

    private static Map<Long, Integer> slotIntermedioLleno(Graph graph) {
        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveSlotIntermedio(graph), CAPACIDAD_ALMACEN);
        return blockAirport;
    }

    private static LuggageBatch batch(String id, int qty, int slaHours) {
        return new LuggageBatch(id, qty, slaHours, "AAA", "CCC",
                LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(7, 0)));
    }

    /** AAA→BBB (08:30-09:30) y BBB→CCC (18:00-19:00): escala de 8h30 en BBB, sin ruta directa. */
    private static Graph grafoEscalaLarga() {
        Graph g = new Graph();
        Node aaa = node("AAA"), bbb = node("BBB"), ccc = node("CCC");
        g.nodes.put("AAA", aaa);
        g.nodes.put("BBB", bbb);
        g.nodes.put("CCC", ccc);
        addEdge(g, 0, aaa, bbb, "F1", "08:30", "09:30", 50);
        addEdge(g, 1, bbb, ccc, "F2", "18:00", "19:00", 50);
        return g;
    }

    private static Node node(String code) {
        Node n = new Node(code);
        n.capacity = CAPACIDAD_ALMACEN;
        return n;
    }

    private static void addEdge(Graph g, int idx, Node from, Node to, String id,
                                String dep, String arr, int cap) {
        Edge e = new Edge();
        e.idx = idx;
        e.id = id;
        e.from = from;
        e.to = to;
        e.capacity = cap;
        e.departureTime = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.parse(dep));
        e.arrivalTime   = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.parse(arr));
        e.departureLocalTime = e.departureTime.toLocalTime();
        e.depMinuteOfDay = e.departureLocalTime.getHour() * 60 + e.departureLocalTime.getMinute();
        e.durationMinutes = (int) Duration.between(e.departureTime, e.arrivalTime).toMinutes();
        e.cost = e.durationMinutes;
        g.addEdge(e);
    }
}
