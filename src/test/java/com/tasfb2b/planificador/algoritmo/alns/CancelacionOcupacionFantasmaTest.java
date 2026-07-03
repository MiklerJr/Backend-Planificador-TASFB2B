package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresión (hallazgo "ocupación fantasma tras cancelación + purga"): al cancelar un vuelo en
 * vivo, los envíos afectados se reencolan como replanificables con su ruta rota aún COMMITEADA a
 * la ocupación global; la liberación ({@code releaseFromGlobal} + {@code clearRoute}) es diferida
 * al momento en que salen del backlog en un bloque posterior. Si el envío VENCÍA antes de salir
 * (p. ej. por el tope {@code max-reproceso-por-bloque}), {@code GestorBacklog.purgarVencidas} lo
 * descartaba SIN liberar: la capacidad de los vuelos posteriores y la estadía de almacén de una
 * ruta físicamente imposible quedaban cobradas para siempre, y el envío seguía contando como
 * "enrutado".
 *
 * <p>Comportamiento corregido (verificado aquí): el hook de descarte del backlog — espejo de
 * {@code PlanificadorService.crearBacklogConPurga} — libera la ocupación global y limpia la ruta
 * de los purgados cuya ruta usa un vuelo cancelado. Los replanificables preventivos con ruta
 * válida on-time salen del backlog sin liberarse ni contarse como {@code sinRutaDefinitivo}:
 * su entrega dentro del SLA ya está comprometida.
 */
class CancelacionOcupacionFantasmaTest {

    private static final int CAPACIDAD_VUELO = 50;
    private static final int CAPACIDAD_ALMACEN = 500;
    private static final LocalDate DIA = LocalDate.of(2026, 1, 1);

    @Test
    void purgarVencidasLiberaLaOcupacionGlobalDeUnaRutaRotaPorCancelacion() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        Arista f1 = graph.edges.get(0);   // AAA→BBB 08:30-09:30
        Arista f2 = graph.edges.get(1);   // BBB→CCC 18:00-19:00

        // 1. Enrutar y commitear B1 por AAA→BBB→CCC (estadía 09:30-18:00 en BBB).
        LoteEnvio b1 = enrutarYCommitear(op);

        long depF2 = OperadorReparacionVoraz.toEpochMinPublic(LocalDateTime.of(DIA, LocalTime.of(18, 0)));
        long estadiaBbb = OperadorReparacionVoraz.toEpochMinPublic(LocalDateTime.of(DIA, LocalTime.of(12, 0)));
        assertEquals(CAPACIDAD_VUELO - 20, op.capacidadRestante(f2, depF2, new HashMap<>()),
                "sanidad: B1 ocupa 20 plazas del tramo BBB→CCC");
        assertEquals(CAPACIDAD_ALMACEN - 20, op.capacidadAlmacen(graph.nodes.get("BBB"), estadiaBbb, new HashMap<>()),
                "sanidad: la estadía de B1 ocupa el almacén de BBB");

        // 2. Cancelación en vivo del primer tramo (F1 del día): B1 ya no puede volar esa ruta.
        long keyF1 = CodificadorClaveVuelo.flightKey(f1.idx,
                OperadorReparacionVoraz.toEpochMinPublic(LocalDateTime.of(DIA, LocalTime.of(8, 30))));
        assertTrue(op.addCancelledFlight(keyF1));

        // 3. Reencolado como replanificable (igual que reencolarAfectadosPorCancelacion) y
        //    purga por vencimiento ANTES de que un bloque lo reprocese (deadline 02/01 07:00).
        GestorBacklog backlog = backlogConHookDeProduccion(op);
        backlog.addReplanificable(b1);
        int purgados = backlog.purgarVencidas(LocalDateTime.of(2026, 1, 2, 8, 0));
        assertEquals(1, purgados, "B1 vence en el backlog sin haber sido reprocesado");
        assertEquals(0, backlog.size());
        assertEquals(1, backlog.sinRutaDefinitivo(),
                "B1 sale sin ruta utilizable → cuenta como sinRutaDefinitivo");

        // 4. Comportamiento corregido: al purgar se libera la ocupación global de la ruta rota.
        assertEquals(CAPACIDAD_VUELO, op.capacidadRestante(f2, depF2, new HashMap<>()),
                "las 20 plazas de BBB→CCC vuelven a estar disponibles al purgar");
        assertEquals(CAPACIDAD_ALMACEN, op.capacidadAlmacen(graph.nodes.get("BBB"), estadiaBbb, new HashMap<>()),
                "la estadía fantasma en BBB se libera al purgar");
        assertTrue(b1.getAssignedRoute() == null || b1.getAssignedRoute().isEmpty(),
                "B1 queda sin ruta: deja de contar como 'enrutado' en métricas/auditoría");
        assertEquals(0, op.capacidadRestante(f1, OperadorReparacionVoraz.toEpochMinPublic(
                        LocalDateTime.of(DIA, LocalTime.of(8, 30))), new HashMap<>()),
                "sanidad: el vuelo cancelado no ofrece capacidad");
    }

    @Test
    void purgarReplanificablePreventivoConRutaValidaNoLiberaNiCuentaComoDefinitivo() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        Arista f2 = graph.edges.get(1);   // BBB→CCC 18:00-19:00

        // B1 enrutado y commiteado SIN cancelación: replanificable preventivo (poca holgura SLA).
        LoteEnvio b1 = enrutarYCommitear(op);
        long depF2 = OperadorReparacionVoraz.toEpochMinPublic(LocalDateTime.of(DIA, LocalTime.of(18, 0)));

        GestorBacklog backlog = backlogConHookDeProduccion(op);
        backlog.addReplanificable(b1);
        int purgados = backlog.purgarVencidas(LocalDateTime.of(2026, 1, 2, 8, 0));

        // Su entrega on-time ya está comprometida: sale del backlog (ya no hay nada que
        // replanificar) pero NO es un vencido real — no libera, no cuenta, no colapsa E3.
        assertEquals(0, purgados, "una ruta válida on-time no es un incumplimiento");
        assertEquals(0, backlog.size(), "sale del backlog: su ventana de replanificación pasó");
        assertEquals(0, backlog.sinRutaDefinitivo(), "no infla el conteo de definitivos");
        assertFalse(b1.getAssignedRoute().isEmpty(), "conserva su ruta commiteada");
        assertEquals(CAPACIDAD_VUELO - 20, op.capacidadRestante(f2, depF2, new HashMap<>()),
                "su ocupación sigue cobrada: la entrega sigue en pie");
    }

    // ----------------------------------------------------------------------- helpers

    /** Enruta B1 (AAA→CCC, 20 maletas, deadline 02/01 07:00) y commitea su ruta a la ocupación global. */
    private static LoteEnvio enrutarYCommitear(OperadorReparacionVoraz op) {
        LoteEnvio b1 = new LoteEnvio("B1", 20, 24, "AAA", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(7, 0)));
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        RutaCandidata ruta = op.generarCandidatosRuta(b1, blockFlight, blockAirport, 3).stream()
                .filter(RutaCandidata::isCumpleSLA)
                .findFirst().orElseThrow();
        op.aplicarCandidatoRuta(b1, ruta);
        op.aplicarCandidatoBloque(b1, ruta, blockFlight, blockAirport);
        op.commitBlock(blockFlight, blockAirport);
        return b1;
    }

    /** Backlog con el mismo hook de descarte que {@code PlanificadorService.crearBacklogConPurga}. */
    private static GestorBacklog backlogConHookDeProduccion(OperadorReparacionVoraz op) {
        return new GestorBacklog(1000, true, b -> {
            if (op.rutaUsaVueloCancelado(b)) {
                op.releaseFromGlobal(b);
                b.clearRoute();
            }
        });
    }

    /** AAA→BBB (08:30-09:30) y BBB→CCC (18:00-19:00): escala de 8h30 en BBB, sin ruta directa. */
    private static Grafo grafoEscalaLarga() {
        Grafo g = new Grafo();
        Nodo aaa = node("AAA"), bbb = node("BBB"), ccc = node("CCC");
        g.nodes.put("AAA", aaa);
        g.nodes.put("BBB", bbb);
        g.nodes.put("CCC", ccc);
        addEdge(g, 0, aaa, bbb, "F1", "08:30", "09:30");
        addEdge(g, 1, bbb, ccc, "F2", "18:00", "19:00");
        return g;
    }

    private static Nodo node(String code) {
        Nodo n = new Nodo(code);
        n.capacity = CAPACIDAD_ALMACEN;
        return n;
    }

    private static void addEdge(Grafo g, int idx, Nodo from, Nodo to, String id, String dep, String arr) {
        Arista e = new Arista();
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
