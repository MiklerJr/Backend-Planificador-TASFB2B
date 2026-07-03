package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.algoritmo.alns.CodificadorClaveVuelo;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.dto.almacenes.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
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
 * (la global del enrutador, que tras {@code confirmarBloque} ya incluye el bloque), no el delta del
 * bloque. El mapa del bloque solo selecciona QUÉ vuelos-día/slots reportar.
 *
 * <p>Antes del fix los DTOs se construían solo con {@code blockFlight}/{@code blockAirport} y el
 * semáforo VERDE/AMBAR/ROJO se calculaba sobre el delta: un vuelo-día al 96% acumulado se mostraba
 * VERDE si el último bloque solo le sumó un 6%. Estos tests reproducen ese escenario y exigen el
 * comportamiento corregido (semáforo sobre el acumulado), espejando el flujo de producción:
 * {@code confirmarBloque} ocurre ANTES de construir los DTOs en {@code procesarBloque}.
 */
class TelemetriaBloqueParcialTest {

    private static final int CAPACIDAD_VUELO = 50;
    private static final int CAPACIDAD_ALMACEN = 500;
    private static final LocalDate DIA = LocalDate.of(2026, 1, 1);

    @Test
    void cargaVueloReportaElAcumuladoGlobalDelVueloDiaAunqueElDeltaDelBloqueSeaPequeno() {
        Grafo graph = grafoUnVuelo();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        Arista vuelo = graph.aristas.get(0);
        long depMin = OperadorReparacionVoraz.aMinutoEpochPublico(LocalDateTime.of(DIA, LocalTime.of(10, 0)));
        long key = CodificadorClaveVuelo.claveVuelo(vuelo.indice, depMin);

        // Bloque 1: 45 maletas commiteadas a la ocupación GLOBAL del vuelo-día.
        Map<Long, Integer> bloque1 = new HashMap<>(Map.of(key, 45));
        op.confirmarBloque(bloque1, new HashMap<>());

        // Bloque 2: 3 maletas más al MISMO vuelo-día.
        Map<Long, Integer> bloque2 = new HashMap<>(Map.of(key, 3));

        // La validación interna (Dijkstra) ve la presión real: 50 - 45 - 3 = 2 plazas.
        assertEquals(2, op.capacidadRestante(vuelo, depMin, bloque2),
                "el modelo interno acumula global + bloque");

        // Como en producción: el bloque se commitea a global ANTES de construir los DTOs.
        op.confirmarBloque(bloque2, new HashMap<>());
        assertEquals(48, op.ocupacionGlobalVuelo(key), "tras el commit, global incluye el bloque");

        // El DTO del bloque 2 reporta el acumulado del vuelo-día: 48/50 = 96% ⇒ ROJO.
        CargaVuelo dto = soloUno(
                new PlanificadorService(null, null, null, null, null, null)
                        .buildCargasVuelos(bloque2, graph, op));
        assertEquals(48, dto.getCargaAsignada(),
                "cargaAsignada = acumulado global del vuelo-día, no el delta del bloque");
        assertEquals(96.0, dto.getPorcentajeCarga(), 0.001);
        assertEquals("ROJO", dto.getSemaforo(),
                "el semáforo se calcula sobre el acumulado (96% > 90% ⇒ ROJO)");
    }

    @Test
    void ocupacionAlmacenReportaElPicoConcurrenteAcumuladoAunqueElDeltaDelBloqueSeaPequeno() {
        Grafo graph = grafoUnVuelo();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        Nodo bbb = graph.nodos.get("BBB");
        long slotMin = OperadorReparacionVoraz.aMinutoEpochPublico(LocalDateTime.of(DIA, LocalTime.of(12, 0)));
        long claveSlot = OperadorReparacionVoraz.claveAlmacenDeSlot(bbb.indice, slotMin);

        // Bloque 1: 480 maletas concurrentes en el slot de las 12:00, commiteadas a global.
        Map<Long, Integer> almacenBloque1 = new HashMap<>(Map.of(claveSlot, 480));
        op.confirmarBloque(new HashMap<>(), almacenBloque1);

        // Bloque 2: 10 maletas más en el mismo slot.
        Map<Long, Integer> almacenBloque2 = new HashMap<>(Map.of(claveSlot, 10));

        // La validación interna ve 500 - 480 - 10 = 10 de capacidad restante (98% ocupado).
        assertEquals(10, op.capacidadAlmacen(bbb, slotMin, almacenBloque2),
                "el modelo interno acumula global + bloque + backlog de origen");

        // Como en producción: el bloque se commitea a global ANTES de construir los DTOs.
        op.confirmarBloque(new HashMap<>(), almacenBloque2);
        assertEquals(490, op.ocupacionGlobalAlmacen(claveSlot), "tras el commit, global incluye el bloque");

        // El DTO del bloque 2 reporta el pico concurrente acumulado: 490/500 = 98% ⇒ ROJO.
        OcupacionAlmacen dto = soloUno(
                new PlanificadorService(null, null, null, null, null, null)
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
    private static Grafo grafoUnVuelo() {
        Grafo g = new Grafo();
        Nodo aaa = node("AAA"), bbb = node("BBB");
        g.nodos.put("AAA", aaa);
        g.nodos.put("BBB", bbb);
        Arista e = new Arista();
        e.indice = 0;
        e.id = "F1";
        e.origen = aaa;
        e.destino = bbb;
        e.capacidad = CAPACIDAD_VUELO;
        e.horaSalida = LocalDateTime.of(DIA, LocalTime.of(10, 0));
        e.horaLlegada = LocalDateTime.of(DIA, LocalTime.of(12, 0));
        e.horaSalidaLocal = e.horaSalida.toLocalTime();
        e.minutoDelDiaSalida = 10 * 60;
        e.duracionMinutos = (int) Duration.between(e.horaSalida, e.horaLlegada).toMinutes();
        e.costo = e.duracionMinutos;
        g.agregarArista(e);
        return g;
    }

    private static Nodo node(String code) {
        Nodo n = new Nodo(code);
        n.capacidad = CAPACIDAD_ALMACEN;
        return n;
    }
}
