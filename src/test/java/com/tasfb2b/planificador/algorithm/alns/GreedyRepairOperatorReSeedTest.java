package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase Q — re-seed de esqueletos hub-avoiding en {@code rutaSkeletonCache}, SIN recomputar en el
 * bucle caliente. Test en el paquete {@code .alns} para acceder a {@code rutaSkeletonCache} y
 * {@code skeletonKey} (package-private). Grafo: AAA→BBB→CCC (vía hub) y AAA→DDD→CCC (vía no-hub),
 * sin ruta directa, así que la única alternativa sin-hub es el detour por DDD.
 */
class GreedyRepairOperatorReSeedTest {

    @Test
    void reSeedInyectaEsqueletoSinHubCuandoLaCacheSoloTieneRutaPorHub() {
        Graph graph = grafoSinDirecto();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        op.setHubs(Set.of("BBB"));               // BBB es hub; DDD no

        int aaa = graph.nodes.get("AAA").idx;
        int ccc = graph.nodes.get("CCC").idx;
        long key = GreedyRepairOperator.skeletonKey(aaa, ccc, 7 * 60, 24);

        // La caché solo conoce la ruta POR el hub (F2=idx0, F3=idx1).
        op.rutaSkeletonCache.put(key, new ArrayList<>(List.of(new int[]{0, 1})));

        op.reSeedHubAvoiding(10, Long.MAX_VALUE);

        List<int[]> sk = op.rutaSkeletonCache.get(key);
        assertTrue(contiene(sk, new int[]{2, 3}),
                "el re-seed inyecta la ruta sin-hub (F4,F5 vía DDD)");
        assertTrue(contiene(sk, new int[]{0, 1}),
                "se conserva la ruta rápida por hub (urgentes intactos)");
        assertEquals(2, sk.size());

        // Idempotente: una clave se intenta una sola vez.
        op.reSeedHubAvoiding(10, Long.MAX_VALUE);
        assertEquals(2, op.rutaSkeletonCache.get(key).size());
    }

    @Test
    void reSeedNoInyectaNadaSiTodasLasRutasPasanPorHub() {
        Graph graph = grafoSinDirecto();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        op.setHubs(Set.of("BBB", "DDD"));        // ambos tránsitos son hub → no hay ruta sin-hub

        int aaa = graph.nodes.get("AAA").idx;
        int ccc = graph.nodes.get("CCC").idx;
        long key = GreedyRepairOperator.skeletonKey(aaa, ccc, 7 * 60, 24);
        op.rutaSkeletonCache.put(key, new ArrayList<>(List.of(new int[]{0, 1})));

        op.reSeedHubAvoiding(10, Long.MAX_VALUE);

        assertEquals(1, op.rutaSkeletonCache.get(key).size(),
                "sin ruta hub-free on-time, el re-seed no agrega nada (solo agrega opciones)");
    }

    @Test
    void reSeedDesactivadoConSliceCeroEsNoOp() {
        Graph graph = grafoSinDirecto();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        op.setHubs(Set.of("BBB"));
        int aaa = graph.nodes.get("AAA").idx, ccc = graph.nodes.get("CCC").idx;
        long key = GreedyRepairOperator.skeletonKey(aaa, ccc, 7 * 60, 24);
        op.rutaSkeletonCache.put(key, new ArrayList<>(List.of(new int[]{0, 1})));

        op.reSeedHubAvoiding(0, Long.MAX_VALUE);   // slice=0 → off

        assertEquals(1, op.rutaSkeletonCache.get(key).size());
    }

    @Test
    void precalentarEsqueletosLlenaLaCacheUnaVezPorClave() {
        // Fase T (N3): pre-calentar puebla rutaSkeletonCache desde la demanda, una sola vez por clave
        // única (origen, destino, hora-del-día, SLA). Dos envíos del mismo (O,D,bucket-hora,SLA) →
        // una sola clave calentada; luego generarCandidatosRuta usa el fast-path (caché caliente).
        Graph graph = grafoSinDirecto();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);

        int aaa = graph.nodes.get("AAA").idx;
        int ccc = graph.nodes.get("CCC").idx;
        long key = GreedyRepairOperator.skeletonKey(aaa, ccc, 7 * 60, 24);   // 07:00 → bucket 7, SLA 24h

        assertTrue(op.rutaSkeletonCache.isEmpty(), "la caché arranca vacía (arranque limpio)");

        // 07:00 y 07:30 caen en el MISMO bucket de hora (7) → misma clave de esqueleto.
        List<LuggageBatch> demanda = List.of(
                new LuggageBatch("E1", 10, 24, "AAA", "CCC", LocalDateTime.of(2026, 1, 1, 7, 0)),
                new LuggageBatch("E2", 10, 24, "AAA", "CCC", LocalDateTime.of(2026, 1, 1, 7, 30)));

        int claves = op.precalentarEsqueletos(demanda, 5);

        assertEquals(1, claves, "dos envíos de la misma clave (O,D,hora-bucket,SLA) → una sola calentada");
        assertTrue(op.rutaSkeletonCache.containsKey(key), "la caché quedó poblada para AAA→CCC@07h/24h");
        assertTrue(!op.rutaSkeletonCache.get(key).isEmpty(), "hay al menos un esqueleto cacheado");

        // El fast-path ya dispone de candidatos sin recomputar Dijkstra.
        List<GreedyRepairOperator.RouteCandidate> cand = op.generarCandidatosRuta(
                new LuggageBatch("E3", 10, 24, "AAA", "CCC", LocalDateTime.of(2026, 1, 1, 7, 15)),
                new java.util.HashMap<>(), new java.util.HashMap<>(), 5);
        assertTrue(!cand.isEmpty(), "tras el pre-warm, generarCandidatosRuta devuelve candidatos");
    }

    // ----------------------------------------------------------------------- helpers
    private static boolean contiene(List<int[]> lista, int[] objetivo) {
        for (int[] s : lista) if (Arrays.equals(s, objetivo)) return true;
        return false;
    }

    private static Graph grafoSinDirecto() {
        Graph g = new Graph();
        Node aaa = node("AAA"), bbb = node("BBB"), ddd = node("DDD"), ccc = node("CCC");
        g.nodes.put("AAA", aaa);
        g.nodes.put("BBB", bbb);
        g.nodes.put("DDD", ddd);
        g.nodes.put("CCC", ccc);
        addEdge(g, 0, aaa, bbb, "F2", "08:30", "09:30", 50);   // vía hub
        addEdge(g, 1, bbb, ccc, "F3", "10:00", "11:00", 50);
        addEdge(g, 2, aaa, ddd, "F4", "09:00", "10:00", 50);   // vía no-hub (detour)
        addEdge(g, 3, ddd, ccc, "F5", "10:30", "11:30", 50);
        return g;
    }

    private static Node node(String code) {
        Node n = new Node(code);
        n.capacity = 500;
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
