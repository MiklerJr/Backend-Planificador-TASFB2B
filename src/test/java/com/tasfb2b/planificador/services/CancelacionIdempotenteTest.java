package com.tasfb2b.planificador.services;
import com.tasfb2b.planificador.services.jobs.JobState;

import com.tasfb2b.planificador.algorithm.grafo.Edge;
import com.tasfb2b.planificador.algorithm.grafo.Graph;
import com.tasfb2b.planificador.algorithm.grafo.Node;
import com.tasfb2b.planificador.algorithm.alns.FlightKeyEncoder;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.dto.vuelos.CancelacionVueloRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idempotencia de la cancelación de vuelo en vivo (Q6: picos de CPU por doble-click). Verifica las
 * dos barreras que evitan que una MISMA orden se procese dos veces (lo que disparaba doble consulta
 * a BD + doble reencolado/reproceso):
 * <ol>
 *   <li><b>Dedup en el encolado</b> ({@link JobState#encolarCancelacionVuelo}): una orden idéntica ya
 *       pendiente no se vuelve a encolar.</li>
 *   <li><b>Set de vuelo-días cancelados</b> ({@link GreedyRepairOperator#addCancelledFlight}): aun si
 *       un duplicado llegara al worker, no produce ningún edge-día NUEVO, por lo que
 *       {@code aplicarCancelacionesVuelo} salta el reencolado (que ahora se hace sobre
 *       {@code edgesCancelados}, no sobre {@code matches}).</li>
 * </ol>
 */
class CancelacionIdempotenteTest {

    @Test
    void encolarOrdenIdenticaDosVecesNoDuplicaLaCola() {
        JobState job = new JobState("job-1", "1", 1);

        CancelacionVueloRequest o1 = orden("AAA", "BBB", LocalDateTime.of(2026, 1, 1, 8, 30));
        CancelacionVueloRequest o2 = orden("AAA", "BBB", LocalDateTime.of(2026, 1, 1, 8, 30)); // idéntica

        assertTrue(job.encolarCancelacionVuelo(o1), "la primera orden se encola");
        assertFalse(job.encolarCancelacionVuelo(o2), "el doble-click idéntico NO se reencola");
        assertEquals(1, job.getCancelacionesVueloPendientes().size(), "la cola no acumula duplicados");

        // Una orden distinta (otra hora) sí entra: el dedup es por orden exacta, no global.
        CancelacionVueloRequest o3 = orden("AAA", "BBB", LocalDateTime.of(2026, 1, 1, 9, 30));
        assertTrue(job.encolarCancelacionVuelo(o3), "una orden distinta sí se encola");
        assertEquals(2, job.getCancelacionesVueloPendientes().size());
    }

    @Test
    void marcarElMismoVueloDiaDosVecesNoLoCancelaDeNuevo() {
        Graph graph = grafoMinimo();
        GreedyRepairOperator op = new GreedyRepairOperator(graph);
        long key = FlightKeyEncoder.flightKey(graph.edges.get(0).idx, 0L);

        assertTrue(op.addCancelledFlight(key), "primera marca: vuelo-día NUEVO cancelado");
        assertFalse(op.addCancelledFlight(key),
                "duplicado: ya estaba cancelado ⇒ edgesCancelados vacío ⇒ sin reencolado/consulta a BD");
    }

    private static CancelacionVueloRequest orden(String origen, String destino, LocalDateTime salida) {
        CancelacionVueloRequest o = new CancelacionVueloRequest();
        o.setOrigen(origen);
        o.setDestino(destino);
        o.setFechaHoraSalida(salida);
        return o;
    }

    private static Graph grafoMinimo() {
        Graph g = new Graph();
        Node aaa = new Node("AAA");
        Node bbb = new Node("BBB");
        g.nodes.put("AAA", aaa);
        g.nodes.put("BBB", bbb);
        Edge e = new Edge();
        e.idx = 0;
        e.id = "F1";
        e.from = aaa;
        e.to = bbb;
        e.capacity = 50;
        e.departureTime = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(8, 30));
        e.arrivalTime = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(9, 30));
        e.departureLocalTime = e.departureTime.toLocalTime();
        e.depMinuteOfDay = 8 * 60 + 30;
        e.durationMinutes = (int) Duration.between(e.departureTime, e.arrivalTime).toMinutes();
        e.cost = e.durationMinutes;
        g.addEdge(e);
        return g;
    }
}
