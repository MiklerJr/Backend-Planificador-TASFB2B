package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.algoritmo.alns.RutaCandidata;
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
 * contabilidad de capacidad del {@link OperadorReparacionVoraz} (lo más delicado) y el estado de
 * {@link LoteEnvio} con prefijo. El flujo completo (corte por el "ahora", recomposición, varado)
 * se valida e2e contra la BD.
 */
class ReenrutadoDesdePosicionTest {

    private static final int CAPACIDAD_VUELO = 50;
    private static final int CAPACIDAD_ALMACEN = 500;
    private static final LocalDate DIA = LocalDate.of(2026, 1, 1);

    /** T2 — {@code liberarSufijoDeGlobal} libera SOLO el sufijo y la estadía del nodo de corte;
     *  el prefijo (vuelo ya volado) sigue ocupado. */
    @Test
    void releaseSuffixLiberaSoloElSufijoYConservaElPrefijo() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        Arista f1 = graph.aristas.get(0);   // AAA→BBB (prefijo)
        Arista f2 = graph.aristas.get(1);   // BBB→CCC (sufijo)
        LoteEnvio b1 = enrutarYCommitear(op);   // ruta AAA→BBB→CCC, 20 maletas

        long depF1 = epoch(8, 30);
        long depF2 = epoch(18, 0);
        long estadiaBbb = epoch(12, 0);

        // Sanidad: los dos tramos y la estadía en BBB están ocupados.
        assertEquals(CAPACIDAD_VUELO - 20, op.capacidadRestante(f1, depF1, new HashMap<>()));
        assertEquals(CAPACIDAD_VUELO - 20, op.capacidadRestante(f2, depF2, new HashMap<>()));
        assertEquals(CAPACIDAD_ALMACEN - 20,
                op.capacidadAlmacen(graph.nodos.get("BBB"), estadiaBbb, new HashMap<>()));

        // Corte en k=1: el envío ya voló AAA→BBB; se libera solo el sufijo (BBB→CCC + estadía BBB).
        op.liberarSufijoDeGlobal(b1, 1);

        assertEquals(CAPACIDAD_VUELO - 20, op.capacidadRestante(f1, depF1, new HashMap<>()),
                "el prefijo AAA→BBB SIGUE ocupado (vuelo ya volado)");
        assertEquals(CAPACIDAD_VUELO, op.capacidadRestante(f2, depF2, new HashMap<>()),
                "el sufijo BBB→CCC se libera");
        assertEquals(CAPACIDAD_ALMACEN,
                op.capacidadAlmacen(graph.nodos.get("BBB"), estadiaBbb, new HashMap<>()),
                "la estadía vieja del nodo de corte BBB se libera (su límite viejo ya no aplica)");
    }

    /** T4 — el SLA del sufijo se mide contra el deadline ABSOLUTO del envío original, no desde la escala. */
    @Test
    void cumpleSlaDesdeOrigenMideLaFechaLimiteAbsoluta() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        // Candidato de sufijo BBB→CCC (sale 18:00, llega 19:00 del DIA).
        LoteEnvio desdeEscala = new LoteEnvio("S", 10, 24, "BBB", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(10, 0)));
        RutaCandidata sufijo = op.generarCandidatosRuta(desdeEscala, new HashMap<>(), new HashMap<>(), 3)
                .stream().findFirst().orElseThrow();

        // Envío original registrado a las 07:00 con SLA 48h → deadline holgado → on-time.
        LoteEnvio holgado = new LoteEnvio("B", 10, 48, "AAA", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(7, 0)));
        assertTrue(op.cumpleSlaDesdeOrigen(sufijo, holgado),
                "llega 19:00, deadline +48h: cumple SLA real");

        // Mismo registro pero SLA 1h → deadline 08:00, muy anterior a la llegada (19:00): tardío.
        LoteEnvio apretado = new LoteEnvio("B2", 10, 1, "AAA", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(7, 0)));
        assertFalse(op.cumpleSlaDesdeOrigen(sufijo, apretado),
                "llega 19:00, deadline 08:00: NO cumple (el sufijo 'parece' on-time desde la escala)");
    }

    /** Estado de LoteEnvio con prefijo: ruta completa, posición efectiva y clonado. */
    @Test
    void prefijoComponeRutaCompletaYPosicionEfectiva() {
        Grafo graph = grafoEscalaLarga();
        Arista f1 = graph.aristas.get(0), f2 = graph.aristas.get(1);
        LoteEnvio b = new LoteEnvio("B", 10, 24, "AAA", "CCC",
                LocalDateTime.of(DIA, LocalTime.of(7, 0)));

        assertFalse(b.tienePrefijo());
        assertEquals("AAA", b.origenEfectivo());                 // sin prefijo: posición = origen

        // Simular un corte: prefijo = [f1], sufijo = [f2], posición = BBB.
        b.setPrefijoFijo(new ArrayList<>(List.of(f1)));
        b.setPrefijoFijoSalidas(new ArrayList<>(List.of(100L)));
        b.setOrigenActual("BBB");
        b.setTiempoListoActual(LocalDateTime.of(DIA, LocalTime.of(9, 30)));
        b.setRutaAsignada(new ArrayList<>(List.of(f2)));
        b.setSalidasAsignadas(new ArrayList<>(List.of(200L)));

        assertTrue(b.tienePrefijo());
        assertEquals("BBB", b.origenEfectivo());
        assertEquals(LocalDateTime.of(DIA, LocalTime.of(9, 30)), b.tiempoListoEfectivo());
        assertEquals(List.of(f1, f2), b.getRutaCompleta());
        assertEquals(List.of(100L, 200L), b.getSalidasCompletas());

        LoteEnvio clon = b.clonar();
        assertTrue(clon.tienePrefijo(), "clonar preserva el prefijo");
        assertEquals("BBB", clon.origenEfectivo());
        assertEquals(List.of(f1, f2), clon.getRutaCompleta());
    }

    // ----------------------------------------------------------------------- helpers

    private static long epoch(int h, int m) {
        return OperadorReparacionVoraz.aMinutoEpochPublico(LocalDateTime.of(DIA, LocalTime.of(h, m)));
    }

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
        op.confirmarBloque(blockFlight, blockAirport);
        return b1;
    }

    private static Grafo grafoEscalaLarga() {
        Grafo g = new Grafo();
        Nodo aaa = node("AAA"), bbb = node("BBB"), ccc = node("CCC");
        g.nodos.put("AAA", aaa);
        g.nodos.put("BBB", bbb);
        g.nodos.put("CCC", ccc);
        agregarArista(g, 0, aaa, bbb, "F1", "08:30", "09:30");
        agregarArista(g, 1, bbb, ccc, "F2", "18:00", "19:00");
        return g;
    }

    private static Nodo node(String code) {
        Nodo n = new Nodo(code);
        n.capacidad = CAPACIDAD_ALMACEN;
        return n;
    }

    private static void agregarArista(Grafo g, int idx, Nodo from, Nodo to, String id, String dep, String arr) {
        Arista e = new Arista();
        e.indice = idx;
        e.id = id;
        e.origen = from;
        e.destino = to;
        e.capacidad = CAPACIDAD_VUELO;
        e.horaSalida = LocalDateTime.of(DIA, LocalTime.parse(dep));
        e.horaLlegada = LocalDateTime.of(DIA, LocalTime.parse(arr));
        e.horaSalidaLocal = e.horaSalida.toLocalTime();
        e.minutoDelDiaSalida = e.horaSalidaLocal.getHour() * 60 + e.horaSalidaLocal.getMinute();
        e.duracionMinutos = (int) Duration.between(e.horaSalida, e.horaLlegada).toMinutes();
        e.costo = e.duracionMinutos;
        g.agregarArista(e);
    }
}
