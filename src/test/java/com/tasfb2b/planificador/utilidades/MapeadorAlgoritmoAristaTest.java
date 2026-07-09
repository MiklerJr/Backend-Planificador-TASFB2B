package com.tasfb2b.planificador.utilidades;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code construirArista} (usado por las altas EN CALIENTE) debe producir una arista IDÉNTICA a la
 * que sale del bucle de {@code mapearAGrafo}, para que agregar un vuelo en caliente sea equivalente a
 * reconstruir el grafo. Cubre los casos delicados de la normalización UTC (oeste, medianoche, este).
 */
class MapeadorAlgoritmoAristaTest {

    private final MapeadorAlgoritmo mapper = new MapeadorAlgoritmo();

    @Test
    void construirAristaEquivaleAlBucleDeMapearAGrafo() {
        // Tres vuelos que ejercen los tres caminos de floorMod: oeste corto, cruce de medianoche, este.
        List<Aeropuerto> aeropuertos = List.of(
                aeropuerto("LBSF", 3), aeropuerto("LDZA", 2),
                aeropuerto("VIDP", 5), aeropuerto("SKBO", -5),
                aeropuerto("LATI", 2));
        List<Vuelo> vuelos = List.of(
                vuelo("LBSF", 3, "LDZA", 2, dt(4, 25), dt(4, 14)),   // oeste corto
                vuelo("VIDP", 5, "SKBO", -5, dt(23, 0), dt(6, 0)),   // cruza medianoche
                vuelo("LATI", 2, "LBSF", 3, dt(10, 0), dt(11, 26))); // este normal

        Grafo grafo = mapper.mapearAGrafo(aeropuertos, vuelos);

        // El grafo del "otro lado" (nodos) para reconstruir aristas sueltas con el helper.
        Grafo soloNodos = new Grafo();
        for (Aeropuerto a : aeropuertos) soloNodos.agregarNodo(a.getCodigo(), 0);

        for (int i = 0; i < vuelos.size(); i++) {
            Arista esperada = grafo.aristas.get(i);
            Arista actual = MapeadorAlgoritmo.construirArista(vuelos.get(i), soloNodos, i);
            assertEquals(esperada.indice, actual.indice, "indice");
            assertEquals(esperada.origen.codigo, actual.origen.codigo, "origen");
            assertEquals(esperada.destino.codigo, actual.destino.codigo, "destino");
            assertEquals(esperada.duracionMinutos, actual.duracionMinutos, "duracionMinutos");
            assertEquals(esperada.minutoDelDiaSalida, actual.minutoDelDiaSalida, "minutoDelDiaSalida");
            assertEquals(esperada.horaSalida, actual.horaSalida, "horaSalida (UTC)");
            assertEquals(esperada.horaLlegada, actual.horaLlegada, "horaLlegada (UTC)");
            assertEquals(esperada.capacidad, actual.capacidad, "capacidad");
            assertEquals(esperada.costo, actual.costo, "costo");
        }
    }

    private static LocalDateTime dt(int h, int m) {
        return LocalDateTime.of(2026, 1, 1, h, m);
    }

    private static Aeropuerto aeropuerto(String codigo, int offset) {
        Aeropuerto a = new Aeropuerto();
        a.setCodigo(codigo);
        a.setOffsetHorario(offset);
        a.setCapacidad(500);
        return a;
    }

    private static Vuelo vuelo(String origen, int offOrig, String destino, int offDest,
                               LocalDateTime salida, LocalDateTime llegada) {
        Vuelo v = new Vuelo();
        v.setOrigen(origen);
        v.setDestino(destino);
        v.setCapacidad(300);
        v.setFechaHoraSalida(salida);
        v.setFechaHoraLlegada(llegada);
        v.setAeropuertoOrigen(aeropuerto(origen, offOrig));
        v.setAeropuertoDestino(aeropuerto(destino, offDest));
        return v;
    }
}
