package com.tasfb2b.planificador.utilidades;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * La duración de un vuelo se calcula con los husos y un único módulo 24h. Un vuelo hacia el
 * oeste más corto que su diferencia de huso aterriza a una hora de pared menor que la de salida
 * SIN cruzar medianoche; antes esto inflaba la duración en 24h (LBSF→LDZA, LBSF→LATI).
 */
class MapeadorAlgoritmoDuracionTest {

    private final MapeadorAlgoritmo mapper = new MapeadorAlgoritmo();

    @Test
    void vueloHaciaElOesteMasCortoQueElHusoNoInflaLaDuracion() {
        // LBSF Sofía (+3) → LDZA Zagreb (+2): 04:25 local → 04:14 local destino. Real = 49 min.
        Grafo g = mapper.mapToGraph(
                List.of(aeropuerto("LBSF", 3), aeropuerto("LDZA", 2)),
                List.of(vuelo("LBSF", 3, "LDZA", 2, dt(4, 25), dt(4, 14))));
        Arista e = edge(g, "LBSF", "LDZA");
        assertEquals(49, e.durationMinutes, "LBSF→LDZA debe durar 49 min, no 24h49");
    }

    @Test
    void vueloQueCruzaMedianocheRealConservaSuDuracion() {
        // VIDP Delhi (+5) 23:00 → SKBO Bogotá (−5) 06:00 del día siguiente. Real = 17h = 1020 min.
        Grafo g = mapper.mapToGraph(
                List.of(aeropuerto("VIDP", 5), aeropuerto("SKBO", -5)),
                List.of(vuelo("VIDP", 5, "SKBO", -5, dt(23, 0), dt(6, 0))));
        Arista e = edge(g, "VIDP", "SKBO");
        assertEquals(1020, e.durationMinutes, "VIDP→SKBO debe durar 1020 min (17h)");
    }

    @Test
    void vueloNormalHaciaElEsteSinCambios() {
        // LATI Tirana (+2) → LBSF Sofía (+3): 10:00 → 11:26 local destino. Real = 26 min.
        Grafo g = mapper.mapToGraph(
                List.of(aeropuerto("LATI", 2), aeropuerto("LBSF", 3)),
                List.of(vuelo("LATI", 2, "LBSF", 3, dt(10, 0), dt(11, 26))));
        Arista e = edge(g, "LATI", "LBSF");
        assertEquals(26, e.durationMinutes);
    }

    private static Arista edge(Grafo g, String from, String to) {
        Arista found = g.edges.stream()
                .filter(e -> from.equals(e.from.code) && to.equals(e.to.code))
                .findFirst().orElse(null);
        assertNotNull(found, "no se encontró la arista " + from + "→" + to);
        return found;
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
