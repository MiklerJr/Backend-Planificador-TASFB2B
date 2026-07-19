package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase Q — re-seed de esqueletos hub-avoiding en {@code rutaCacheEsqueleto}, SIN recomputar en el
 * bucle caliente. Test en el paquete {@code .alns} para acceder a {@code rutaCacheEsqueleto} y
 * {@code skeletonKey} (package-private). Grafo: AAA→BBB→CCC (vía hub) y AAA→DDD→CCC (vía no-hub),
 * sin ruta directa, así que la única alternativa sin-hub es el detour por DDD.
 */
class OperadorReparacionVorazReSeedTest {

    @Test
    void reSeedInyectaEsqueletoSinHubCuandoLaCacheSoloTieneRutaPorHub() {
        Grafo graph = grafoSinDirecto();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        op.setHubs(Set.of("BBB"));               // BBB es hub; DDD no

        int aaa = graph.nodos.get("AAA").indice;
        int ccc = graph.nodos.get("CCC").indice;
        long key = OperadorReparacionVoraz.skeletonKey(aaa, ccc, 7 * 60, 24);

        // La caché solo conoce la ruta POR el hub (F2=idx0, F3=idx1).
        op.rutaCacheEsqueleto.put(key, new ArrayList<>(List.of(new int[]{0, 1})));

        op.reSeedHubAvoiding(10, Long.MAX_VALUE);

        List<int[]> sk = op.rutaCacheEsqueleto.get(key);
        assertTrue(contiene(sk, new int[]{2, 3}),
                "el re-seed inyecta la ruta sin-hub (F4,F5 vía DDD)");
        assertTrue(contiene(sk, new int[]{0, 1}),
                "se conserva la ruta rápida por hub (urgentes intactos)");
        assertEquals(2, sk.size());

        // Idempotente: una clave se intenta una sola vez.
        op.reSeedHubAvoiding(10, Long.MAX_VALUE);
        assertEquals(2, op.rutaCacheEsqueleto.get(key).size());
    }

    @Test
    void reSeedNoInyectaNadaSiTodasLasRutasPasanPorHub() {
        Grafo graph = grafoSinDirecto();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        op.setHubs(Set.of("BBB", "DDD"));        // ambos tránsitos son hub → no hay ruta sin-hub

        int aaa = graph.nodos.get("AAA").indice;
        int ccc = graph.nodos.get("CCC").indice;
        long key = OperadorReparacionVoraz.skeletonKey(aaa, ccc, 7 * 60, 24);
        op.rutaCacheEsqueleto.put(key, new ArrayList<>(List.of(new int[]{0, 1})));

        op.reSeedHubAvoiding(10, Long.MAX_VALUE);

        assertEquals(1, op.rutaCacheEsqueleto.get(key).size(),
                "sin ruta hub-free on-time, el re-seed no agrega nada (solo agrega opciones)");
    }

    @Test
    void reSeedDesactivadoConSliceCeroEsNoOp() {
        Grafo graph = grafoSinDirecto();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        op.setHubs(Set.of("BBB"));
        int aaa = graph.nodos.get("AAA").indice, ccc = graph.nodos.get("CCC").indice;
        long key = OperadorReparacionVoraz.skeletonKey(aaa, ccc, 7 * 60, 24);
        op.rutaCacheEsqueleto.put(key, new ArrayList<>(List.of(new int[]{0, 1})));

        op.reSeedHubAvoiding(0, Long.MAX_VALUE);   // slice=0 → off

        assertEquals(1, op.rutaCacheEsqueleto.get(key).size());
    }

    @Test
    void precalentarEsqueletosLlenaLaCacheUnaVezPorClave() {
        // Fase T (N3): pre-calentar puebla rutaCacheEsqueleto desde la demanda, una sola vez por clave
        // única (origen, destino, hora-del-día, SLA). Dos envíos del mismo (O,D,bucket-hora,SLA) →
        // una sola clave calentada; luego generarCandidatosRuta usa el fast-path (caché caliente).
        Grafo graph = grafoSinDirecto();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);

        int aaa = graph.nodos.get("AAA").indice;
        int ccc = graph.nodos.get("CCC").indice;
        long key = OperadorReparacionVoraz.skeletonKey(aaa, ccc, 7 * 60, 24);   // 07:00 → bucket 7, SLA 24h

        assertTrue(op.rutaCacheEsqueleto.isEmpty(), "la caché arranca vacía (arranque limpio)");

        // 07:00 y 07:30 caen en el MISMO bucket de hora (7) → misma clave de esqueleto.
        List<LoteEnvio> demanda = List.of(
                new LoteEnvio("E1", 10, 24, "AAA", "CCC", LocalDateTime.of(2026, 1, 1, 7, 0)),
                new LoteEnvio("E2", 10, 24, "AAA", "CCC", LocalDateTime.of(2026, 1, 1, 7, 30)));

        int claves = op.precalentarEsqueletos(demanda, 5);

        assertEquals(1, claves, "dos envíos de la misma clave (O,D,hora-bucket,SLA) → una sola calentada");
        assertTrue(op.rutaCacheEsqueleto.containsKey(key), "la caché quedó poblada para AAA→CCC@07h/24h");
        assertTrue(!op.rutaCacheEsqueleto.get(key).isEmpty(), "hay al menos un esqueleto cacheado");

        // El fast-path ya dispone de candidatos sin recomputar Dijkstra.
        List<RutaCandidata> cand = op.generarCandidatosRuta(
                new LoteEnvio("E3", 10, 24, "AAA", "CCC", LocalDateTime.of(2026, 1, 1, 7, 15)),
                new java.util.HashMap<>(), new java.util.HashMap<>(), 5);
        assertTrue(!cand.isEmpty(), "tras el pre-warm, generarCandidatosRuta devuelve candidatos");
    }

    @Test
    void precalentarEsqueletosAbortaCuandoSePideCancelar() {
        // Pre-warm cancelable: con caché fría cada clave cuesta un Dijkstra, así que el check de
        // cancelación corta el bucle entre claves en vez de esperar a calentar toda la ventana.
        // Lo ya calentado se conserva (la corrida siguiente continúa desde ahí).
        Grafo graph = grafoSinDirecto();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);

        // Tres claves distintas (buckets de hora 7, 8 y 9 del mismo O→D).
        List<LoteEnvio> demanda = List.of(
                new LoteEnvio("E1", 10, 24, "AAA", "CCC", LocalDateTime.of(2026, 1, 1, 7, 0)),
                new LoteEnvio("E2", 10, 24, "AAA", "CCC", LocalDateTime.of(2026, 1, 1, 8, 0)),
                new LoteEnvio("E3", 10, 24, "AAA", "CCC", LocalDateTime.of(2026, 1, 1, 9, 0)));

        // La cancelación llega tras calentar la primera clave (el check corre antes de cada una).
        AtomicInteger checks = new AtomicInteger();
        int claves = op.precalentarEsqueletos(demanda, 5, () -> checks.incrementAndGet() > 1);

        assertEquals(1, claves, "cancelado tras la primera clave: el resto no se calienta");
        int aaa = graph.nodos.get("AAA").indice, ccc = graph.nodos.get("CCC").indice;
        assertTrue(op.rutaCacheEsqueleto.containsKey(OperadorReparacionVoraz.skeletonKey(aaa, ccc, 7 * 60, 24)),
                "lo calentado antes de cancelar se conserva en la caché");
        assertTrue(!op.rutaCacheEsqueleto.containsKey(OperadorReparacionVoraz.skeletonKey(aaa, ccc, 9 * 60, 24)),
                "las claves posteriores a la cancelación no se calientan");

        // Sin supplier (null) se comporta como la variante de dos argumentos: intenta las 3 claves
        // (la 1.ª resuelve por fast-path de caché; el contador es de claves intentadas por llamada).
        assertEquals(3, op.precalentarEsqueletos(demanda, 5, null),
                "sin cancelación se intentan todas las claves de la demanda");
        assertTrue(op.rutaCacheEsqueleto.containsKey(OperadorReparacionVoraz.skeletonKey(aaa, ccc, 9 * 60, 24)),
                "tras el pre-warm completo la clave que faltaba queda calentada");
    }

    // ----------------------------------------------------------------------- helpers
    private static boolean contiene(List<int[]> lista, int[] objetivo) {
        for (int[] s : lista) if (Arrays.equals(s, objetivo)) return true;
        return false;
    }

    private static Grafo grafoSinDirecto() {
        Grafo g = new Grafo();
        Nodo aaa = node("AAA"), bbb = node("BBB"), ddd = node("DDD"), ccc = node("CCC");
        g.nodos.put("AAA", aaa);
        g.nodos.put("BBB", bbb);
        g.nodos.put("DDD", ddd);
        g.nodos.put("CCC", ccc);
        agregarArista(g, 0, aaa, bbb, "F2", "08:30", "09:30", 50);   // vía hub
        agregarArista(g, 1, bbb, ccc, "F3", "10:00", "11:00", 50);
        agregarArista(g, 2, aaa, ddd, "F4", "09:00", "10:00", 50);   // vía no-hub (detour)
        agregarArista(g, 3, ddd, ccc, "F5", "10:30", "11:30", 50);
        return g;
    }

    private static Nodo node(String code) {
        Nodo n = new Nodo(code);
        n.capacidad = 500;
        return n;
    }

    private static void agregarArista(Grafo g, int idx, Nodo from, Nodo to, String id,
                                String dep, String arr, int cap) {
        Arista e = new Arista();
        e.indice = idx;
        e.id = id;
        e.origen = from;
        e.destino = to;
        e.capacidad = cap;
        e.horaSalida = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.parse(dep));
        e.horaLlegada   = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.parse(arr));
        e.horaSalidaLocal = e.horaSalida.toLocalTime();
        e.minutoDelDiaSalida = e.horaSalidaLocal.getHour() * 60 + e.horaSalidaLocal.getMinute();
        e.duracionMinutos = (int) Duration.between(e.horaSalida, e.horaLlegada).toMinutes();
        e.costo = e.duracionMinutos;
        g.agregarArista(e);
    }
}
