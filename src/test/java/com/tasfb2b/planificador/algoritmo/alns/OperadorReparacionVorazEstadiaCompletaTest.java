package com.tasfb2b.planificador.algoritmo.alns;


import com.tasfb2b.planificador.algoritmo.aco.ColoniaACO;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.algoritmo.alns.RutaCandidata;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
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
 * y el {@code reparar()} del ALNS, más el motor ACO end-to-end. Grafo con escala LARGA en BBB
 * (llega 09:30, sale 18:00): un slot INTERMEDIO lleno (13:00, distinto del de llegada) debe
 * rechazar la ruta — antes solo se validaba el slot de llegada y los intermedios se cobraban
 * sin chequear (overflow del almacén).
 */
class OperadorReparacionVorazEstadiaCompletaTest {

    private static final int CAPACIDAD_ALMACEN = 500;

    @Test
    void dijkstraHijoRechazaRutaSiUnSlotIntermedioDeLaEscalaEstaLleno() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        LoteEnvio batch = batch("B1", 20, 24);

        // Sanidad: sin ocupación, la ruta del día 1 existe y es on-time.
        List<RutaCandidata> libres = op.generarCandidatosRuta(batch, new HashMap<>(), new HashMap<>(), 3);
        assertTrue(libres.stream().anyMatch(RutaCandidata::isCumpleSLA),
                "sin ocupación la ruta vía BBB del día 1 es on-time");

        // Slot intermedio (13:00) de la estadía en BBB lleno; el de llegada (09:30) queda libre.
        Map<Long, Integer> blockAirport = slotIntermedioLleno(graph);
        List<RutaCandidata> candidatos = op.generarCandidatosRuta(batch, new HashMap<>(), blockAirport, 3);

        assertTrue(candidatos.stream().noneMatch(RutaCandidata::isCumpleSLA),
                "con un slot intermedio de la estadía lleno, la ruta del día 1 no debe ofrecerse");
    }

    @Test
    void materializarRutaCandidataRechazaSiUnSlotIntermedioDeLaEscalaEstaLleno() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        LoteEnvio batch = batch("B1", 20, 24);

        List<Arista> rutaViaBbb = List.of(graph.aristas.get(0), graph.aristas.get(1));
        assertTrue(op.materializarRutaCandidata(batch, rutaViaBbb, new HashMap<>(), new HashMap<>())
                        .isCumpleSLA(),
                "sin ocupación, el esqueleto cacheado materializa on-time");

        assertNull(op.materializarRutaCandidata(batch, rutaViaBbb, new HashMap<>(), slotIntermedioLleno(graph)),
                "la materialización debe revalidar la estadía completa, no solo el slot de llegada");
    }

    @Test
    void repairNoCobraSlotsDeEstadiaNuncaValidados() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        LoteEnvio batch = batch("B1", 20, 24);

        // El slot intermedio queda a 10 maletas del tope: el batch (20) NO cabe en él.
        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveSlotIntermedio(graph), CAPACIDAD_ALMACEN - 10);
        Map<Long, Integer> blockFlight = new HashMap<>();

        op.reparar(new SolucionAlns(List.of(batch)), List.of(batch), blockFlight, blockAirport);

        Nodo bbb = graph.nodos.get("BBB");
        for (Map.Entry<Long, Integer> e : blockAirport.entrySet()) {
            assertTrue(e.getValue() <= bbb.capacidad,
                    "ningún slot de almacén queda sobre capacidad tras reparar: "
                            + e.getValue() + "/" + bbb.capacidad);
        }
        if (batch.getRutaAsignada() != null && !batch.getRutaAsignada().isEmpty()) {
            long primeraSalida = batch.getSalidasAsignadas().get(0);
            long readyMin = OperadorReparacionVoraz.aMinutoEpochPublico(batch.getTiempoListo());
            assertTrue(primeraSalida - readyMin > 24 * 60,
                    "si enruta, debe ser en un día posterior (la estadía del día 1 no cabe)");
        }
    }

    @Test
    void acoPadreNoSobrepasaCapacidadDeAlmacenEnSlotsIntermedios() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        ColoniaACO engine = new ColoniaACO(new PlanificadorProperties());
        LoteEnvio batch = batch("B1", 20, 24);

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveSlotIntermedio(graph), CAPACIDAD_ALMACEN - 10);

        engine.procesar(graph, op, List.of(batch), blockFlight, blockAirport, new Random(7L), 1_000L);

        Nodo bbb = graph.nodos.get("BBB");
        for (Map.Entry<Long, Integer> e : blockAirport.entrySet()) {
            assertTrue(e.getValue() <= bbb.capacidad,
                    "ningún slot de almacén queda sobre capacidad tras el ACO: "
                            + e.getValue() + "/" + bbb.capacidad);
        }
        // Día 1 no cabe (slot intermedio) y el día 2 es tardío (F1 no confirma tardías):
        // el envío queda sinRuta, jamás cobrado sobre un slot lleno.
        assertFalse(batch.isCumpleSLA());
        assertTrue(batch.getRutaAsignada() == null || batch.getRutaAsignada().isEmpty());
    }

    @Test
    void sinRutaPorAlmacenLlenoClasificaElDesbordeDeEstadiaComoColapso() {
        // Señal de COLAPSO que detiene la simulación (PlanificadorService 3b): un envío que no
        // logra ruta on-time respetando almacenes pero SÍ la tendría ignorándolos = le llegaron
        // maletas a un almacén que quedaría en sobrecapacidad. Debe dispararse también cuando lo
        // que bloquea es un slot INTERMEDIO de la estadía, no solo el de llegada.
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        LoteEnvio batch = batch("B1", 20, 24);

        assertFalse(op.sinRutaPorAlmacenLleno(batch),
                "con almacenes libres no hay colapso: la ruta on-time existe");

        // Slot intermedio de la estadía en BBB lleno, commiteado a la ocupación GLOBAL
        // (sinRutaPorAlmacenLleno lee el estado global post-commit).
        op.confirmarBloque(new HashMap<>(), slotIntermedioLleno(graph));

        assertTrue(op.sinRutaPorAlmacenLleno(batch),
                "el desborde de estadía debe clasificarse como colapso por almacén lleno");
    }

    @Test
    void evaluarPreColapsoReportaDesbordeDuroSobreElCienPorCiento() {
        // Señal del freno DURO (PlanificadorService): utilización > 1.0 en un slot tocado por el
        // bloque ⇒ ocupación real sobre capacidad ⇒ la simulación se detiene de inmediato.
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);

        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveSlotIntermedio(graph), CAPACIDAD_ALMACEN + 50);
        op.confirmarBloque(new HashMap<>(), blockAirport);

        PreColapso pre = op.evaluarPreColapso(blockAirport, List.of());

        assertTrue(pre.utilAlmacenMax() > 1.0,
                "una ocupación sobre capacidad debe reportar utilización > 100%");
        assertEquals("BBB", pre.almacenCritico());
    }

    // ----------------------------------------------------------------------- helpers

    /** Slot de las 13:00 del día 1 en BBB: dentro de la estadía [09:30, 18:00) pero NO el de llegada. */
    private static long claveSlotIntermedio(Grafo graph) {
        long epochMin = OperadorReparacionVoraz.aMinutoEpochPublico(
                LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(13, 0)));
        return OperadorReparacionVoraz.claveAlmacenDeSlot(graph.nodos.get("BBB").indice, epochMin);
    }

    private static Map<Long, Integer> slotIntermedioLleno(Grafo graph) {
        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveSlotIntermedio(graph), CAPACIDAD_ALMACEN);
        return blockAirport;
    }

    private static LoteEnvio batch(String id, int qty, int slaHours) {
        return new LoteEnvio(id, qty, slaHours, "AAA", "CCC",
                LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(7, 0)));
    }

    /** AAA→BBB (08:30-09:30) y BBB→CCC (18:00-19:00): escala de 8h30 en BBB, sin ruta directa. */
    private static Grafo grafoEscalaLarga() {
        Grafo g = new Grafo();
        Nodo aaa = node("AAA"), bbb = node("BBB"), ccc = node("CCC");
        g.nodos.put("AAA", aaa);
        g.nodos.put("BBB", bbb);
        g.nodos.put("CCC", ccc);
        agregarArista(g, 0, aaa, bbb, "F1", "08:30", "09:30", 50);
        agregarArista(g, 1, bbb, ccc, "F2", "18:00", "19:00", 50);
        return g;
    }

    private static Nodo node(String code) {
        Nodo n = new Nodo(code);
        n.capacidad = CAPACIDAD_ALMACEN;
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
