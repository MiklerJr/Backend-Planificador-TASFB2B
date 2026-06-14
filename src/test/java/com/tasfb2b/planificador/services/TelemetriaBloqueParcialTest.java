package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
import com.tasfb2b.planificador.algorithm.alns.FlightKeyEncoder;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.dto.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verificación del fix del hallazgo "telemetría parcial por bloque": {@code BloqueSimulacion.cargasVuelos}
 * y {@code ocupacionAlmacenes} reportan la ocupación ACUMULADA de cada recurso tocado por el bloque
 * (la global del enrutador, que tras {@code commitBlock} ya incluye el bloque), no el delta del
 * bloque. El mapa del bloque solo selecciona QUÉ vuelos-día/slots reportar.
 *
 * <p>Antes del fix los DTOs se construían solo con {@code blockFlight}/{@code blockAirport} y el
 * semáforo VERDE/AMBAR/ROJO se calculaba sobre el delta: un vuelo-día al 96% acumulado se mostraba
 * VERDE si el último bloque solo le sumó un 6%. Estos tests reproducen ese escenario y exigen el
 * comportamiento corregido (semáforo sobre el acumulado), espejando el flujo de producción:
 * {@code commitBlock} ocurre ANTES de construir los DTOs en {@code procesarBloque}.
 */
class TelemetriaBloqueParcialTest {

    private static final int CAPACIDAD_VUELO = 50;
    private static final int CAPACIDAD_ALMACEN = 500;
    private static final LocalDate DIA = LocalDate.of(2026, 1, 1);

    @Test
    void cargaVueloReportaElAcumuladoGlobalDelVueloDiaAunqueElDeltaDelBloqueSeaPequeno() {
        Graph graph = grafoUnVuelo();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        Edge vuelo = graph.edges.get(0);
        long depMin = GreedyRepairOperator.toEpochMinPublic(LocalDateTime.of(DIA, LocalTime.of(10, 0)));
        long key = FlightKeyEncoder.flightKey(vuelo.idx, depMin);

        // Bloque 1: 45 maletas commiteadas a la ocupación GLOBAL del vuelo-día.
        Map<Long, Integer> bloque1 = new HashMap<>(Map.of(key, 45));
        op.commitBlock(bloque1, new HashMap<>());

        // Bloque 2: 3 maletas más al MISMO vuelo-día.
        Map<Long, Integer> bloque2 = new HashMap<>(Map.of(key, 3));

        // La validación interna (Dijkstra) ve la presión real: 50 - 45 - 3 = 2 plazas.
        assertEquals(2, op.capacidadRestante(vuelo, depMin, bloque2),
                "el modelo interno acumula global + bloque");

        // Como en producción: el bloque se commitea a global ANTES de construir los DTOs.
        op.commitBlock(bloque2, new HashMap<>());
        assertEquals(48, op.ocupacionGlobalVuelo(key), "tras el commit, global incluye el bloque");

        // El DTO del bloque 2 reporta el acumulado del vuelo-día: 48/50 = 96% ⇒ ROJO.
        CargaVuelo dto = soloUno(
                new PlanificadorService(null, null, null, null, null, null, null)
                        .buildCargasVuelos(bloque2, graph, op));
        assertEquals(48, dto.getCargaAsignada(),
                "cargaAsignada = acumulado global del vuelo-día, no el delta del bloque");
        assertEquals(96.0, dto.getPorcentajeCarga(), 0.001);
        assertEquals("ROJO", dto.getSemaforo(),
                "el semáforo se calcula sobre el acumulado (96% > 90% ⇒ ROJO)");
    }

    @Test
    void ocupacionAlmacenReportaElPicoConcurrenteAcumuladoAunqueElDeltaDelBloqueSeaPequeno() {
        Graph graph = grafoUnVuelo();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        Node bbb = graph.nodes.get("BBB");
        long slotMin = GreedyRepairOperator.toEpochMinPublic(LocalDateTime.of(DIA, LocalTime.of(12, 0)));
        long slotKey = GreedyRepairOperator.claveAlmacenDeSlot(bbb.idx, slotMin);

        // Bloque 1: 480 maletas concurrentes en el slot de las 12:00, commiteadas a global.
        Map<Long, Integer> almacenBloque1 = new HashMap<>(Map.of(slotKey, 480));
        op.commitBlock(new HashMap<>(), almacenBloque1);

        // Bloque 2: 10 maletas más en el mismo slot.
        Map<Long, Integer> almacenBloque2 = new HashMap<>(Map.of(slotKey, 10));

        // La validación interna ve 500 - 480 - 10 = 10 de capacidad restante (98% ocupado).
        assertEquals(10, op.capacidadAlmacen(bbb, slotMin, almacenBloque2),
                "el modelo interno acumula global + bloque + backlog de origen");

        // Como en producción: el bloque se commitea a global ANTES de construir los DTOs.
        op.commitBlock(new HashMap<>(), almacenBloque2);
        assertEquals(490, op.ocupacionGlobalAlmacen(slotKey), "tras el commit, global incluye el bloque");

        // El DTO del bloque 2 reporta el pico concurrente acumulado: 490/500 = 98% ⇒ ROJO.
        OcupacionAlmacen dto = soloUno(
                new PlanificadorService(null, null, null, null, null, null, null)
                        .buildOcupacionAlmacenes(almacenBloque2, graph, op));
        assertEquals(490, dto.getOcupacionAsignada(),
                "ocupacionAsignada = pico concurrente acumulado del día, no el delta del bloque");
        assertEquals(98.0, dto.getPorcentajeOcupacion(), 0.001);
        assertEquals("ROJO", dto.getSemaforo(),
                "el semáforo se calcula sobre el acumulado (98% > 90% ⇒ ROJO)");
    }

    // ----------------------------------------------------------------------- helpers

    private static <T> T soloUno(List<T> lista) {
        assertEquals(1, lista.size(), "se esperaba exactamente un DTO");
        return lista.get(0);
    }

    /** AAA→BBB (10:00-12:00), capacidad 50; almacenes de 500. */
    private static Graph grafoUnVuelo() {
        Graph g = new Graph();
        Node aaa = node("AAA"), bbb = node("BBB");
        g.nodes.put("AAA", aaa);
        g.nodes.put("BBB", bbb);
        Edge e = new Edge();
        e.idx = 0;
        e.id = "F1";
        e.from = aaa;
        e.to = bbb;
        e.capacity = CAPACIDAD_VUELO;
        e.departureTime = LocalDateTime.of(DIA, LocalTime.of(10, 0));
        e.arrivalTime = LocalDateTime.of(DIA, LocalTime.of(12, 0));
        e.departureLocalTime = e.departureTime.toLocalTime();
        e.depMinuteOfDay = 10 * 60;
        e.durationMinutes = (int) Duration.between(e.departureTime, e.arrivalTime).toMinutes();
        e.cost = e.durationMinutes;
        g.addEdge(e);
        return g;
    }

    private static Node node(String code) {
        Node n = new Node(code);
        n.capacity = CAPACIDAD_ALMACEN;
        return n;
    }
}
