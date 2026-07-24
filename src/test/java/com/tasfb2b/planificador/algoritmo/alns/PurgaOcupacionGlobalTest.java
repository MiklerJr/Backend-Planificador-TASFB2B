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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 — purga por antigüedad de la ocupación global ({@code ocupacionVuelo} /
 * {@code ocupacionAeropuerto}). En una corrida larga esos mapas crecían sin tope aunque nada
 * volviera a consultar los días pasados. La purga debe: (a) liberar SOLO los días anteriores al
 * corte, (b) dejar intacta la ocupación vigente y (c) NO cambiar la clasificación de hubs, que es
 * lo único histórico que sigue leyéndose (y que sí afecta al ruteo).
 */
class PurgaOcupacionGlobalTest {

    private static final LocalDate DIA_VIEJO = LocalDate.of(2026, 1, 1);
    private static final LocalDate DIA_VIGENTE = LocalDate.of(2026, 1, 20);
    private static final int CAPACIDAD_ALMACEN = 100;

    @Test
    void purgaSoloLosDiasAnterioresAlCorteYConservaLaOcupacionVigente() {
        Grafo grafo = grafoSimple();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(grafo);
        confirmar(op, grafo, DIA_VIEJO, 30);
        confirmar(op, grafo, DIA_VIGENTE, 40);

        assertEquals(2, op.tamañoOcupacionGlobal()[0], "un vuelo-día por cada fecha cargada");
        assertEquals(2, op.tamañoOcupacionGlobal()[1], "un slot de almacén por cada fecha cargada");

        int purgadas = op.purgarOcupacionAnteriorA(DIA_VIGENTE.toEpochDay());

        assertEquals(2, purgadas, "solo las dos claves del día viejo (vuelo + almacén)");
        assertEquals(1, op.tamañoOcupacionGlobal()[0]);
        assertEquals(1, op.tamañoOcupacionGlobal()[1]);
        assertEquals(40, op.ocupacionGlobalVuelo(claveVuelo(grafo, DIA_VIGENTE)),
                "la ocupación del día vigente no se toca");
        assertEquals(0, op.ocupacionGlobalVuelo(claveVuelo(grafo, DIA_VIEJO)),
                "el día purgado deja de ocupar asientos");
        assertEquals(40, op.ocupacionGlobalAlmacen(claveAlmacen(grafo, DIA_VIGENTE)));
    }

    @Test
    void laPurgaNoCambiaLaClasificacionDeHubs() {
        // Dos enrutadores gemelos: uno se purga y el otro no. El pico histórico del almacén está
        // en el día viejo (90/100 = 90% ≥ umbral 0.55); el día vigente solo llega al 10%.
        Grafo grafoPurgado = grafoSimple();
        OperadorReparacionVoraz purgado = new OperadorReparacionVoraz(grafoPurgado);
        confirmar(purgado, grafoPurgado, DIA_VIEJO, 90);
        confirmar(purgado, grafoPurgado, DIA_VIGENTE, 10);

        Grafo grafoIntacto = grafoSimple();
        OperadorReparacionVoraz intacto = new OperadorReparacionVoraz(grafoIntacto);
        confirmar(intacto, grafoIntacto, DIA_VIEJO, 90);
        confirmar(intacto, grafoIntacto, DIA_VIGENTE, 10);

        purgado.purgarOcupacionAnteriorA(DIA_VIGENTE.toEpochDay());

        purgado.reclasificarHubsPorUtilizacion(0.55);
        intacto.reclasificarHubsPorUtilizacion(0.55);

        int idxDestino = grafoPurgado.nodos.get("BBB").indice;
        assertTrue(intacto.esHub(idxDestino), "sin purgar, el pico del día viejo hace hub a BBB");
        assertEquals(intacto.esHub(idxDestino), purgado.esHub(idxDestino),
                "el pico histórico sobrevive a la purga: misma clasificación de hubs");
    }

    // ----------------------------------------------------------------------- helpers

    /** Carga {@code cantidad} maletas en el vuelo-día y en el slot de almacén de destino de {@code dia}. */
    private static void confirmar(OperadorReparacionVoraz op, Grafo grafo, LocalDate dia, int cantidad) {
        Map<Long, Integer> vuelos = new HashMap<>();
        Map<Long, Integer> almacenes = new HashMap<>();
        vuelos.put(claveVuelo(grafo, dia), cantidad);
        almacenes.put(claveAlmacen(grafo, dia), cantidad);
        op.confirmarBloque(vuelos, almacenes);
    }

    private static long claveVuelo(Grafo grafo, LocalDate dia) {
        return CodificadorClaveVuelo.claveVuelo(grafo.aristas.get(0).indice, minutoEpoch(dia, LocalTime.of(8, 0)));
    }

    private static long claveAlmacen(Grafo grafo, LocalDate dia) {
        return OperadorReparacionVoraz.claveAlmacenDeSlot(
                grafo.nodos.get("BBB").indice, minutoEpoch(dia, LocalTime.of(10, 0)));
    }

    private static long minutoEpoch(LocalDate dia, LocalTime hora) {
        return OperadorReparacionVoraz.aMinutoEpochPublico(LocalDateTime.of(dia, hora));
    }

    private static Grafo grafoSimple() {
        Grafo g = new Grafo();
        Nodo aaa = nodo("AAA"), bbb = nodo("BBB");
        g.nodos.put("AAA", aaa);
        g.nodos.put("BBB", bbb);
        Arista e = new Arista();
        e.indice = 0;
        e.id = "AAA-BBB-0800";
        e.origen = aaa;
        e.destino = bbb;
        e.capacidad = 200;
        e.horaSalida = LocalDateTime.of(DIA_VIEJO, LocalTime.of(8, 0));
        e.horaLlegada = LocalDateTime.of(DIA_VIEJO, LocalTime.of(10, 0));
        e.horaSalidaLocal = e.horaSalida.toLocalTime();
        e.minutoDelDiaSalida = 8 * 60;
        e.duracionMinutos = (int) Duration.between(e.horaSalida, e.horaLlegada).toMinutes();
        e.costo = e.duracionMinutos;
        g.agregarArista(e);
        return g;
    }

    private static Nodo nodo(String codigo) {
        Nodo n = new Nodo(codigo);
        n.capacidad = CAPACIDAD_ALMACEN;
        return n;
    }
}
