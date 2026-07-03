package com.tasfb2b.planificador.servicios;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.algoritmo.alns.CodificadorClaveVuelo;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
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
 *   <li><b>Dedup en el encolado</b> ({@link EstadoJob#encolarCancelacionVuelo}): una orden idéntica ya
 *       pendiente no se vuelve a encolar.</li>
 *   <li><b>Set de vuelo-días cancelados</b> ({@link OperadorReparacionVoraz#agregarVueloCancelado}): aun si
 *       un duplicado llegara al worker, no produce ningún edge-día NUEVO, por lo que
 *       {@code aplicarCancelacionesVuelo} salta el reencolado (que ahora se hace sobre
 *       {@code edgesCancelados}, no sobre {@code matches}).</li>
 * </ol>
 */
class CancelacionIdempotenteTest {

    @Test
    void encolarOrdenIdenticaDosVecesNoDuplicaLaCola() {
        EstadoJob job = new EstadoJob("job-1", "1", 1);

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
        Grafo graph = grafoMinimo();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        long key = CodificadorClaveVuelo.claveVuelo(graph.aristas.get(0).indice, 0L);

        assertTrue(op.agregarVueloCancelado(key), "primera marca: vuelo-día NUEVO cancelado");
        assertFalse(op.agregarVueloCancelado(key),
                "duplicado: ya estaba cancelado ⇒ edgesCancelados vacío ⇒ sin reencolado/consulta a BD");
    }

    private static CancelacionVueloRequest orden(String origen, String destino, LocalDateTime salida) {
        CancelacionVueloRequest o = new CancelacionVueloRequest();
        o.setOrigen(origen);
        o.setDestino(destino);
        o.setFechaHoraSalida(salida);
        return o;
    }

    private static Grafo grafoMinimo() {
        Grafo g = new Grafo();
        Nodo aaa = new Nodo("AAA");
        Nodo bbb = new Nodo("BBB");
        g.nodos.put("AAA", aaa);
        g.nodos.put("BBB", bbb);
        Arista e = new Arista();
        e.indice = 0;
        e.id = "F1";
        e.origen = aaa;
        e.destino = bbb;
        e.capacidad = 50;
        e.horaSalida = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(8, 30));
        e.horaLlegada = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(9, 30));
        e.horaSalidaLocal = e.horaSalida.toLocalTime();
        e.minutoDelDiaSalida = 8 * 60 + 30;
        e.duracionMinutos = (int) Duration.between(e.horaSalida, e.horaLlegada).toMinutes();
        e.costo = e.duracionMinutos;
        g.agregarArista(e);
        return g;
    }
}
