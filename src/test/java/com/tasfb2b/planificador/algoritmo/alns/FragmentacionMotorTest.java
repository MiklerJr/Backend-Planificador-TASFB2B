package com.tasfb2b.planificador.algoritmo.alns;


import com.tasfb2b.planificador.algoritmo.aco.ColoniaACO;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.utilidades.FragmentadorEnvios;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valor de la fragmentación end-to-end en el motor (semilla fija, sin BD ni Spring): un envío de
 * 250 maletas con vuelos directos de capacidad 100 NO se enruta sin fragmentar (el motor exige el
 * lote COMPLETO en un vuelo), pero fragmentado en 3 sub-lotes (84/83/83) se reparte entre los tres
 * vuelos paralelos del día y se entrega la cantidad exacta. Se cubren los dos motores por su núcleo
 * de ruteo compartido: el {@code reparar()} voraz (ALNS) y {@link ColoniaACO} (ACO).
 *
 * <p>Grafo: tres vuelos directos AAA→BBB el mismo día (08:00, 12:00, 16:00 UTC), cada uno cap 100.
 */
class FragmentacionMotorTest {

    private static final int CAP_VUELO = 100;
    private static final int CANTIDAD  = 250;   // 2,5 × cap: no cabe en ningún vuelo sin fragmentar

    @Test
    void sinFragmentarElEnvioGiganteNoSeEnruta_greedy() {
        Grafo graph = grafoTresVuelos();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        LoteEnvio envio = envio("SKBO-000000001", CANTIDAD);

        op.reparar(new SolucionAlns(List.of(envio)), List.of(envio), new HashMap<>(), new HashMap<>());

        assertTrue(rutaVacia(envio), "250 > 100 en cualquier vuelo: sin fragmentar no hay ruta");
    }

    @Test
    void fragmentadoLosSubLotesSeRepartenEntreLosVuelos_greedy() {
        Grafo graph = grafoTresVuelos();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);

        List<LoteEnvio> subs = FragmentadorEnvios.fragmentar(envio("SKBO-000000001", CANTIDAD), CAP_VUELO, 64);
        assertEquals(3, subs.size());

        // Mapas de bloque COMPARTIDOS: cada reparar acumula la capacidad usada (como procesarBloque).
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        for (LoteEnvio sub : subs) {
            op.reparar(new SolucionAlns(List.of(sub)), List.of(sub), blockFlight, blockAirport);
        }

        assertEquals(3, contarEnrutados(subs), "los 3 sub-lotes caben, uno por vuelo");
        assertEquals(CANTIDAD, sumaEnrutada(subs), "la suma de sub-lotes entregados conserva la cantidad");
        assertTrue(subs.stream().allMatch(LoteEnvio::isCumpleSLA), "cada sub-lote llega on-time");
    }

    @Test
    void fragmentadoLosSubLotesSeRepartenEntreLosVuelos_aco() {
        Grafo graph = grafoTresVuelos();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        ColoniaACO engine = new ColoniaACO(new PlanificadorProperties());

        List<LoteEnvio> subs = FragmentadorEnvios.fragmentar(envio("SKBO-000000001", CANTIDAD), CAP_VUELO, 64);

        engine.procesar(graph, op, subs, new HashMap<>(), new HashMap<>(), new Random(7L), 1_000L);

        assertEquals(3, contarEnrutados(subs), "el ACO reparte los 3 sub-lotes entre los vuelos");
        assertEquals(CANTIDAD, sumaEnrutada(subs), "la suma de sub-lotes entregados conserva la cantidad");
    }

    @Test
    void sinFragmentarElEnvioGiganteNoSeEnruta_aco() {
        Grafo graph = grafoTresVuelos();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        ColoniaACO engine = new ColoniaACO(new PlanificadorProperties());
        LoteEnvio envio = envio("SKBO-000000001", CANTIDAD);

        engine.procesar(graph, op, List.of(envio), new HashMap<>(), new HashMap<>(), new Random(7L), 1_000L);

        assertTrue(rutaVacia(envio), "el ACO tampoco enruta un lote de 250 con vuelos de 100");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean rutaVacia(LoteEnvio b) {
        return b.getRutaAsignada() == null || b.getRutaAsignada().isEmpty();
    }

    private static long contarEnrutados(List<LoteEnvio> lotes) {
        return lotes.stream().filter(b -> !rutaVacia(b)).count();
    }

    private static int sumaEnrutada(List<LoteEnvio> lotes) {
        return lotes.stream().filter(b -> !rutaVacia(b)).mapToInt(LoteEnvio::getCantidad).sum();
    }

    private static LoteEnvio envio(String id, int cantidad) {
        return new LoteEnvio(id, cantidad, 24, "AAA", "BBB",
                LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(6, 0)));
    }

    private static Grafo grafoTresVuelos() {
        Grafo g = new Grafo();
        Nodo aaa = node("AAA"), bbb = node("BBB");
        g.nodos.put("AAA", aaa);
        g.nodos.put("BBB", bbb);
        agregarArista(g, 0, aaa, bbb, "V1", "08:00", "10:00", CAP_VUELO);
        agregarArista(g, 1, aaa, bbb, "V2", "12:00", "14:00", CAP_VUELO);
        agregarArista(g, 2, aaa, bbb, "V3", "16:00", "18:00", CAP_VUELO);
        return g;
    }

    private static Nodo node(String code) {
        Nodo n = new Nodo(code);
        n.capacidad = 100_000;          // almacén holgado: el cuello de botella es el vuelo, no el almacén
        n.capacidadAlmacen = 100_000;
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
        e.horaLlegada = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.parse(arr));
        e.horaSalidaLocal = e.horaSalida.toLocalTime();
        e.minutoDelDiaSalida = e.horaSalidaLocal.getHour() * 60 + e.horaSalidaLocal.getMinute();
        e.duracionMinutos = (int) Duration.between(e.horaSalida, e.horaLlegada).toMinutes();
        e.costo = e.duracionMinutos;
        g.agregarArista(e);
    }
}
