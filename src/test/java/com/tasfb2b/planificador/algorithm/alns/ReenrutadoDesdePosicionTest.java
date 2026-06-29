package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.grafo.Edge;
import com.tasfb2b.planificador.algorithm.grafo.Graph;
import com.tasfb2b.planificador.algorithm.grafo.Node;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator.RouteCandidate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase 2 — re-enrutamiento desde la posición física tras una cancelación. Verifica las piezas de
 * contabilidad de capacidad del {@link GreedyRepairOperator} (lo más delicado) y el estado de
 * {@link LuggageBatch} con prefijo. El flujo completo (corte por el "ahora", recomposición, varado)
 * se valida e2e contra la BD.
 */
class ReenrutadoDesdePosicionTest {

    private static final int CAPACIDAD_VUELO = 50;
    private static final int CAPACIDAD_ALMACEN = 500;
    private static final LocalDate DIA = LocalDate.of(2026, 1, 1);

    /** T2 — {@code releaseSuffixFromGlobal} libera SOLO el sufijo y la estadía del nodo de corte;
     *  el prefijo (vuelo ya volado) sigue ocupado. */
    @Test
    void releaseSuffixLiberaSoloElSufijoYConservaElPrefijo() {
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        Edge f1 = graph.edges.get(0);   // AAA→BBB (prefijo)
        Edge f2 = graph.edges.get(1);   // BBB→CCC (sufijo)
        LuggageBatch b1 = enrutarYCommitear(op);   // ruta AAA→BBB→CCC, 20 maletas

        long depF1 = epoch(8, 30);
        long depF2 = epoch(18, 0);
        long estadiaBbb = epoch(12, 0);

        // Sanidad: los dos tramos y la estadía en BBB están ocupados.
        assertEquals(CAPACIDAD_VUELO - 20, op.capacidadRestante(f1, depF1, new HashMap<>()));
        assertEquals(CAPACIDAD_VUELO - 20, op.capacidadRestante(f2, depF2, new HashMap<>()));
        assertEquals(CAPACIDAD_ALMACEN - 20,
                op.capacidadAlmacen(graph.nodes.get("BBB"), estadiaBbb, new HashMap<>()));

        // Corte en k=1: el envío ya voló AAA→BBB; se libera solo el sufijo (BBB→CCC + estadía BBB).
        op.releaseSuffixFromGlobal(b1, 1);

        assertEquals(CAPACIDAD_VUELO - 20, op.capacidadRestante(f1, depF1, new HashMap<>()),
                "el prefijo AAA→BBB SIGUE ocupado (vuelo ya volado)");
        assertEquals(CAPACIDAD_VUELO, op.capacidadRestante(f2, depF2, new HashMap<>()),
                "el sufijo BBB→CCC se libera");
        assertEquals(CAPACIDAD_ALMACEN,
                op.capacidadAlmacen(graph.nodes.get("BBB"), estadiaBbb, new HashMap<>()),
                "la estadía vieja del nodo de corte BBB se libera (su límite viejo ya no aplica)");
    }

    /** T4 — el SLA del sufijo se mide contra el deadline ABSOLUTO del envío original, no desde la escala. */
    @Test
    void cumpleSlaDesdeOrigenMideElDeadlineAbsoluto() {
        Graph graph = grafoEscalaLarga();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        // Candidato de sufijo BBB→CCC (sale 18:00, llega 19:00 del DIA).
        LuggageBatch desdeEscala = new LuggageBatch("S", 10, 24, "BBB", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(10, 0)));
        RouteCandidate sufijo = op.generarCandidatosRuta(desdeEscala, new HashMap<>(), new HashMap<>(), 3)
                .stream().findFirst().orElseThrow();

        // Envío original registrado a las 07:00 con SLA 48h → deadline holgado → on-time.
        LuggageBatch holgado = new LuggageBatch("B", 10, 48, "AAA", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(7, 0)));
        assertTrue(op.cumpleSlaDesdeOrigen(sufijo, holgado),
                "llega 19:00, deadline +48h: cumple SLA real");

        // Mismo registro pero SLA 1h → deadline 08:00, muy anterior a la llegada (19:00): tardío.
        LuggageBatch apretado = new LuggageBatch("B2", 10, 1, "AAA", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(7, 0)));
        assertFalse(op.cumpleSlaDesdeOrigen(sufijo, apretado),
                "llega 19:00, deadline 08:00: NO cumple (el sufijo 'parece' on-time desde la escala)");
    }

    /** Estado de LuggageBatch con prefijo: ruta completa, posición efectiva y clonado. */
    @Test
    void prefijoComponeRutaCompletaYPosicionEfectiva() {
        Graph graph = grafoEscalaLarga();
        Edge f1 = graph.edges.get(0), f2 = graph.edges.get(1);
        LuggageBatch b = new LuggageBatch("B", 10, 24, "AAA", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(7, 0)));

        assertFalse(b.tienePrefijo());
        assertEquals("AAA", b.origenEfectivo());                 // sin prefijo: posición = origen

        // Simular un corte: prefijo = [f1], sufijo = [f2], posición = BBB.
        b.setPrefijoFijo(new ArrayList<>(List.of(f1)));
        b.setPrefijoFijoDepartures(new ArrayList<>(List.of(100L)));
        b.setCurrentOriginCode("BBB");
        b.setCurrentReadyTime(LocalDateTime.of(DIA, LocalTime.of(9, 30)));
        b.setAssignedRoute(new ArrayList<>(List.of(f2)));
        b.setAssignedDepartures(new ArrayList<>(List.of(200L)));

        assertTrue(b.tienePrefijo());
        assertEquals("BBB", b.origenEfectivo());
        assertEquals(LocalDateTime.of(DIA, LocalTime.of(9, 30)), b.readyEfectivo());
        assertEquals(List.of(f1, f2), b.getRutaCompleta());
        assertEquals(List.of(100L, 200L), b.getDeparturesCompletas());

        LuggageBatch clon = b.cloneBatch();
        assertTrue(clon.tienePrefijo(), "cloneBatch preserva el prefijo");
        assertEquals("BBB", clon.origenEfectivo());
        assertEquals(List.of(f1, f2), clon.getRutaCompleta());
    }

    // ----------------------------------------------------------------------- helpers

    private static long epoch(int h, int m) {
        return GreedyRepairOperator.toEpochMinPublic(LocalDateTime.of(DIA, LocalTime.of(h, m)));
    }

    private static LuggageBatch enrutarYCommitear(GreedyRepairOperator op) {
        LuggageBatch b1 = new LuggageBatch("B1", 20, 24, "AAA", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(7, 0)));
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        RouteCandidate ruta = op.generarCandidatosRuta(b1, blockFlight, blockAirport, 3).stream()
                .filter(RouteCandidate::isCumpleSLA)
                .findFirst().orElseThrow();
        op.aplicarCandidatoRuta(b1, ruta);
        op.aplicarCandidatoBloque(b1, ruta, blockFlight, blockAirport);
        op.commitBlock(blockFlight, blockAirport);
        return b1;
    }

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
