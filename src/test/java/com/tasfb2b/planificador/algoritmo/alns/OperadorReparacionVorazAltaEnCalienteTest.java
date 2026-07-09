package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Altas EN CALIENTE en el enrutador: {@code incorporarArista}/{@code incorporarNodo} extienden las
 * estructuras congeladas en el constructor sin recrear el operador, y hacen descubribles las rutas
 * nuevas. Append-only (índices al final): ningún índice existente se mueve.
 */
class OperadorReparacionVorazAltaEnCalienteTest {

    @Test
    void incorporarAristaHaceRutaNuevaDescubrible() {
        Grafo g = new Grafo();
        g.nodos.put("AAA", node("AAA"));
        g.nodos.put("CCC", node("CCC"));
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(g);   // sin aristas: AAA→CCC no existe

        LoteEnvio lote = new LoteEnvio("E1", 10, 24, "AAA", "CCC",
                LocalDateTime.of(2026, 1, 1, 7, 0));
        assertTrue(op.generarCandidatosRuta(lote, new HashMap<>(), new HashMap<>(), 5).isEmpty(),
                "sin aristas no hay ruta AAA→CCC");

        Arista nueva = arista(0, g.nodos.get("AAA"), g.nodos.get("CCC"), "AAA-CCC-0800",
                "08:00", "09:00", 50);
        g.agregarArista(nueva);
        assertTrue(op.incorporarArista(nueva), "la arista append-only (idx 0) se incorpora");

        assertFalse(op.generarCandidatosRuta(lote, new HashMap<>(), new HashMap<>(), 5).isEmpty(),
                "tras incorporarArista la ruta AAA→CCC es descubrible");
    }

    @Test
    void incorporarAristaRechazaIndiceNoAppendOnly() {
        Grafo g = new Grafo();
        g.nodos.put("AAA", node("AAA"));
        g.nodos.put("BBB", node("BBB"));
        g.agregarArista(arista(0, g.nodos.get("AAA"), g.nodos.get("BBB"), "AAA-BBB-0800",
                "08:00", "09:00", 50));
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(g);   // aristaPorIndice.length == 1

        Arista colisiona = arista(0, g.nodos.get("AAA"), g.nodos.get("BBB"), "X", "10:00", "11:00", 50);
        assertFalse(op.incorporarArista(colisiona), "un índice ya usado (0) no es append-only ⇒ rechazado");
    }

    @Test
    void incorporarNodoMasAristasPermiteRutaConEscalaPorElNodoNuevo() {
        Grafo g = new Grafo();
        g.nodos.put("AAA", node("AAA"));
        g.nodos.put("BBB", node("BBB"));
        g.agregarArista(arista(0, g.nodos.get("AAA"), g.nodos.get("BBB"), "AAA-BBB-0800",
                "08:00", "09:00", 50));
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(g);

        // Alta de un aeropuerto nuevo CCC y dos vuelos que lo usan como escala: AAA→CCC→BBB… en
        // realidad probamos ruta a CCC (destino nuevo) vía BBB: BBB→CCC.
        Nodo ccc = g.agregarNodo("CCC", 500);
        int idx = op.incorporarNodo(ccc);
        assertTrue(idx >= 0, "el nodo nuevo recibe un índice válido");

        Arista bbbCcc = arista(1, g.nodos.get("BBB"), ccc, "BBB-CCC-0930", "09:30", "10:30", 50);
        g.agregarArista(bbbCcc);
        assertTrue(op.incorporarArista(bbbCcc), "la arista hacia el nodo nuevo se incorpora");

        LoteEnvio lote = new LoteEnvio("E1", 10, 24, "AAA", "CCC",
                LocalDateTime.of(2026, 1, 1, 7, 0));
        assertFalse(op.generarCandidatosRuta(lote, new HashMap<>(), new HashMap<>(), 5).isEmpty(),
                "AAA→CCC (escala en BBB, con nodo+arista nuevos) es descubrible");
    }

    @Test
    void evaluarPreColapsoNoFallaTrasIncorporarNodo() {
        Grafo g = new Grafo();
        g.nodos.put("AAA", node("AAA"));
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(g);
        Nodo bbb = g.agregarNodo("BBB", 500);
        op.incorporarNodo(bbb);
        // No debe lanzar IndexOutOfBounds pese a que los arrays crecieron en caliente.
        op.evaluarPreColapso(new HashMap<>(), List.of());
        op.reclasificarHubsPorUtilizacion(0.9);
    }

    // ----------------------------------------------------------------------- helpers
    private static Nodo node(String code) {
        Nodo n = new Nodo(code);
        n.capacidad = 500;
        n.capacidadAlmacen = 500;
        return n;
    }

    private static Arista arista(int idx, Nodo from, Nodo to, String id,
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
        return e;
    }
}
