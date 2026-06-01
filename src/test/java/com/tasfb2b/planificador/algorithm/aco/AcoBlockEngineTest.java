package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.FlightKeyEncoder;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator.RouteCandidate;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcoBlockEngineTest {

    @Test
    void dijkstraHijoGeneraCandidatosSinAplicarCapacidad() {
        Graph graph = graphConRutasAlternativas();
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        LuggageBatch batch = batch("B1", 10, "AAA", "CCC", 24);

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        List<RouteCandidate> candidatos = enrutador.generarCandidatosRuta(batch, blockFlight, blockAirport, 3);

        assertTrue(candidatos.size() >= 2);
        assertTrue(candidatos.get(0).isCumpleSLA());
        assertTrue(blockFlight.isEmpty(), "Dijkstra hijo solo propone rutas, no confirma capacidad");
        assertTrue(batch.getAssignedRoute().isEmpty(), "Dijkstra hijo no muta el batch al generar candidatos");
    }

    @Test
    void rutaCacheadaSeRevalidaYSeRechazaSiQuedoSaturada() {
        Graph graph = graphConRutasAlternativas();
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        LuggageBatch blocker = batch("B0", 25, "AAA", "CCC", 24);
        LuggageBatch batch = batch("B1", 20, "AAA", "CCC", 24);

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        RouteCandidate direct = enrutador.generarCandidatosRuta(blocker, blockFlight, blockAirport, 3).stream()
                .filter(c -> c.getEdges().size() == 1)
                .findFirst()
                .orElseThrow();
        enrutador.aplicarCandidatoBloque(blocker, direct, blockFlight, blockAirport);
        for (int day = 2; day <= 3; day++) {
            LuggageBatch nextBlocker = batchAt("B0D" + day, 25, "AAA", "CCC", 24, day);
            RouteCandidate nextDirect = enrutador.materializarRutaCandidata(
                    nextBlocker, direct.getEdges(), blockFlight, blockAirport);
            assertNotNull(nextDirect);
            enrutador.aplicarCandidatoBloque(nextBlocker, nextDirect, blockFlight, blockAirport);
        }

        assertNull(enrutador.materializarRutaCandidata(batch, direct.getEdges(), blockFlight, blockAirport),
                "La cache solo guarda esqueletos; la capacidad vigente debe revalidarse");

        List<RouteCandidate> alternativas = enrutador.generarCandidatosRuta(batch, blockFlight, blockAirport, 3);
        assertFalse(alternativas.isEmpty(), "Dijkstra hijo debe buscar alternativa cuando la cache ya no sirve");
        assertTrue(alternativas.stream().anyMatch(c -> c.getEdges().size() > 1));
    }

    @Test
    void acoPadreAsignaElBloqueUsandoCandidatosDijkstra() {
        Graph graph = graphConRutasAlternativas();
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AcoBlockEngine engine = new AcoBlockEngine(new PlanificadorProperties());

        List<LuggageBatch> batches = List.of(
                batch("B1", 20, "AAA", "CCC", 24),
                batch("B2", 20, "AAA", "CCC", 24)
        );
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        int enrutados = engine.procesar(graph, enrutador, batches, blockFlight, blockAirport, new Random(7L), 1_000L);

        assertEquals(2, enrutados);
        assertFalse(blockFlight.isEmpty(), "ACO padre confirma la asignacion sobre el bloque");
        assertTrue(batches.stream().allMatch(b -> b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty()));
        assertTrue(batches.stream().allMatch(LuggageBatch::isCumpleSLA));
    }

    @Test
    void acoNoSobrepasaCapacidadAunConMemoizacionDeFrontier() {
        Graph graph = graphConRutasAlternativas();
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AcoBlockEngine engine = new AcoBlockEngine(new PlanificadorProperties());

        // Varios batches del mismo OD (AAA->CCC) ejercitan el camino de
        // memoizacion del frontier (Fase B) y saturan el vuelo directo F1
        // (cap 25), obligando a repartir por las rutas alternativas de 2 tramos.
        List<LuggageBatch> batches = List.of(
                batch("B1", 20, "AAA", "CCC", 24),
                batch("B2", 20, "AAA", "CCC", 24),
                batch("B3", 20, "AAA", "CCC", 24),
                batch("B4", 20, "AAA", "CCC", 24));

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        int enrutados = engine.procesar(graph, enrutador, batches, blockFlight, blockAirport,
                new Random(11L), 2_000L);

        assertEquals(4, enrutados, "Las rutas alternativas permiten enrutar los 4 batches");
        // Invariante de correctitud de la Fase B: la invalidacion por interseccion
        // de claves garantiza que ninguna asignacion reusada sobrepase capacidad.
        Map<Integer, Edge> porIdx = new HashMap<>();
        for (Edge e : graph.edges) porIdx.put(e.idx, e);
        for (Map.Entry<Long, Integer> entry : blockFlight.entrySet()) {
            int edgeIdx = (int) (entry.getKey() >> FlightKeyEncoder.DAY_BITS);
            Edge edge = porIdx.get(edgeIdx);
            assertNotNull(edge, "flightKey decodifica a una arista valida");
            assertTrue(entry.getValue() <= edge.capacity,
                    "vuelo " + edge.id + " sobre capacidad: " + entry.getValue() + "/" + edge.capacity);
        }
    }

    @Test
    void ordenarPorUrgenciaPriorizaDeadlineAbsolutoNoHorasDeSla() {
        AcoBlockEngine engine = new AcoBlockEngine(new PlanificadorProperties());

        // "viejo": readyTime antiguo (día 1) con SLA largo (48h) → deadline día 3 07:00.
        // "nuevo": readyTime reciente (día 3) con SLA corto (24h) → deadline día 4 07:00.
        // Por horas de SLA, "nuevo" (24h) iría primero; por DEADLINE absoluto debe ir
        // primero "viejo", porque su vencimiento está más cerca (anti-inanición, G1).
        LuggageBatch viejo = batchAt("VIEJO", 10, "AAA", "CCC", 48, 1);
        LuggageBatch nuevo = batchAt("NUEVO", 10, "AAA", "CCC", 24, 3);

        List<LuggageBatch> ordenado = engine.ordenarPorUrgencia(Arrays.asList(nuevo, viejo));

        assertEquals("VIEJO", ordenado.get(0).getId(),
                "el envío con deadline absoluto más cercano va primero");
        assertEquals("NUEVO", ordenado.get(1).getId());
    }

    @Test
    void urgenteTomaElVueloEscasoYElFlexibleSeDesvia() {
        // Fase J: con un vuelo directo escaso (F1, cap 25) y dos envíos AAA->CCC de qty 20
        // (no caben ambos en F1), el URGENTE (SLA 4h: solo el directo llega on-time) debe
        // quedarse con F1 y el FLEXIBLE (SLA 24h) debe ceder y desviarse por una ruta de
        // 2 tramos. Demuestra que la capacidad escasa se reserva para quien la necesita.
        Graph graph = graphConRutasAlternativas();
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AcoBlockEngine engine = new AcoBlockEngine(new PlanificadorProperties());

        LuggageBatch urgente = batch("U", 20, "AAA", "CCC", 4);
        LuggageBatch flexible = batch("F", 20, "AAA", "CCC", 24);

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        int enrutados = engine.procesar(graph, enrutador, Arrays.asList(flexible, urgente),
                blockFlight, blockAirport, new Random(5L), 2_000L);

        assertEquals(2, enrutados, "ambos se enrutan on-time");
        assertTrue(urgente.isCumpleSLA() && flexible.isCumpleSLA());
        assertEquals(1, urgente.getAssignedRoute().size(),
                "el urgente toma el vuelo directo escaso (1 tramo)");
        assertTrue(flexible.getAssignedRoute().size() >= 2,
                "el flexible cede el vuelo escaso y se desvía (>=2 tramos)");
    }

    @Test
    void acoNoConfirmaRutasTardiasLasDifiere() {
        Graph graph = graphConRutasAlternativas();
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AcoBlockEngine engine = new AcoBlockEngine(new PlanificadorProperties());

        // SLA de 1h es imposible: la ruta directa AAA->CCC ya llega ~3h despues
        // del readyTime. Por la Politica 1 (F1) el motor NO confirma rutas tardias:
        // el batch queda sinRuta para diferirse al backlog, no como tardada.
        LuggageBatch batch = batch("B1", 10, "AAA", "CCC", 1);

        int enrutados = engine.procesar(graph, enrutador, List.of(batch),
                new HashMap<>(), new HashMap<>(), new Random(3L), 1_000L);

        assertEquals(0, enrutados, "una ruta tardia no se confirma: se difiere");
        assertTrue(batch.getAssignedRoute() == null || batch.getAssignedRoute().isEmpty(),
                "el batch tardio queda sin ruta asignada");
        assertFalse(batch.isCumpleSLA());
    }

    @Test
    void acoPadreConTaAgotadoNoRompeYDejaRestantesSinRuta() {
        Graph graph = graphConRutasAlternativas();
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AcoBlockEngine engine = new AcoBlockEngine(new PlanificadorProperties());

        List<LuggageBatch> batches = List.of(
                batch("B1", 20, "AAA", "CCC", 24),
                batch("B2", 20, "AAA", "CCC", 24),
                batch("B3", 20, "AAA", "CCC", 24)
        );

        int enrutados = engine.procesar(graph, enrutador, batches, new HashMap<>(), new HashMap<>(), new Random(7L), 1L);

        assertTrue(enrutados >= 0 && enrutados <= batches.size());
        assertTrue(batches.stream()
                .filter(b -> b.getAssignedRoute() == null || b.getAssignedRoute().isEmpty())
                .noneMatch(LuggageBatch::isCumpleSLA));
    }

    @Test
    void contratoSimulacionResponseMantieneCamposPrincipales() {
        assertFields(SimulacionResponse.class,
                "metricas", "totalBloques", "vuelosPlaneados", "aeropuertosInfo", "k", "saMinutos");
        assertFields(SimulacionResponse.Metricas.class,
                "procesadas", "enrutadas", "sinRuta", "cumpleSLA", "tardadas", "maletasIndividuales");
        assertFields(SimulacionResponse.BloqueSimulacion.class,
                "horaInicio", "horaFin", "asignaciones", "cargasVuelos", "ocupacionAlmacenes", "taMs");
        assertFields(SimulacionResponse.AsignacionMaleta.class,
                "batchId", "origen", "destino", "cantidad", "enrutada", "cumpleSLA", "rutaVuelos", "tramos");
    }

    private static LuggageBatch batch(String id, int qty, String origen, String destino, int slaHours) {
        return batchAt(id, qty, origen, destino, slaHours, 1);
    }

    private static LuggageBatch batchAt(String id, int qty, String origen, String destino, int slaHours, int day) {
        return new LuggageBatch(id, qty, slaHours, origen, destino,
                LocalDateTime.of(LocalDate.of(2026, 1, day), LocalTime.of(7, 0)));
    }

    private static Graph graphConRutasAlternativas() {
        Graph graph = new Graph();
        Node aaa = node("AAA");
        Node bbb = node("BBB");
        Node ddd = node("DDD");
        Node ccc = node("CCC");
        graph.nodes.put(aaa.code, aaa);
        graph.nodes.put(bbb.code, bbb);
        graph.nodes.put(ddd.code, ddd);
        graph.nodes.put(ccc.code, ccc);

        int idx = 0;
        addEdge(graph, idx++, aaa, ccc, "F1", "08:00", "10:00", 25);
        addEdge(graph, idx++, aaa, bbb, "F2", "08:30", "09:30", 50);
        addEdge(graph, idx++, bbb, ccc, "F3", "10:00", "11:00", 50);
        addEdge(graph, idx++, aaa, ddd, "F4", "09:00", "10:00", 50);
        addEdge(graph, idx, ddd, ccc, "F5", "10:30", "11:30", 50);
        return graph;
    }

    private static Node node(String code) {
        Node node = new Node(code);
        node.capacity = 500;
        return node;
    }

    private static void addEdge(Graph graph, int idx, Node from, Node to, String id,
                                String dep, String arr, int cap) {
        Edge edge = new Edge();
        edge.idx = idx;
        edge.id = id;
        edge.from = from;
        edge.to = to;
        edge.capacity = cap;
        edge.departureTime = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.parse(dep));
        edge.arrivalTime = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.parse(arr));
        edge.departureLocalTime = edge.departureTime.toLocalTime();
        edge.depMinuteOfDay = edge.departureLocalTime.getHour() * 60 + edge.departureLocalTime.getMinute();
        edge.durationMinutes = (int) java.time.Duration.between(edge.departureTime, edge.arrivalTime).toMinutes();
        edge.cost = edge.durationMinutes;
        graph.addEdge(edge);
    }

    private static void assertFields(Class<?> type, String... expected) {
        Set<String> actual = Arrays.stream(type.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
        for (String field : expected) {
            assertTrue(actual.contains(field), type.getSimpleName() + " conserva campo " + field);
        }
    }
}
