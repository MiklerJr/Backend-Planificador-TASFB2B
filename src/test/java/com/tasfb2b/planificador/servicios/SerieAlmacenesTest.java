package com.tasfb2b.planificador.servicios;


import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.alns.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.dto.almacenes.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verificación de la serie de ocupación por SLOT ({@code buildSerieAlmacenes}) y del
 * REALISMO del bloque al publicarse: la serie debe reflejar EXACTAMENTE lo que el modelo interno
 * cobró slot a slot — espera en origen {@code [registro, primer vuelo)}, estadía de escala
 * {@code [llegada, salida siguiente)}, destino {@code [llegada, +tiempoRecojoDestino)} y la espera
 * en origen de envíos sin ruta del backlog — sin agregaciones que pierdan granularidad (el DTO
 * diario {@code OcupacionAlmacen} colapsa todo a un pico por día; la serie no).
 *
 * <p>Las cuentas esperadas se DERIVAN de {@link OperadorReparacionVoraz#SLOT_ALMACEN_MIN}
 * (ver {@link #slotsQueCubren}), así que el test sigue siendo válido si cambia la granularidad
 * del modelo de almacén: lo que fija es la semántica (qué intervalo se cobra), no el número.
 */
class SerieAlmacenesTest {

    private static final int CAPACIDAD_VUELO = 50;
    private static final int CAPACIDAD_ALMACEN = 500;
    private static final LocalDate DIA = LocalDate.of(2026, 1, 1);

    /**
     * B1 (20 maletas, ready 07:00) vuela AAA→BBB (08:30-09:30) y BBB→CCC (18:00-19:00):
     * espera en origen AAA [07:00, 08:30), escala en BBB [09:30, 18:00) y destino CCC
     * [19:00, 19:15). Con slots de 60 min son 2 + 9 + 1 = 12; con 15 min, 6 + 34 + 1 = 41.
     */
    @Test
    void laSerieReproduceSlotASlotLaEstadiaCompletaQueCobroElModelo() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        enrutar(op, batch("B1", 20, LocalTime.of(7, 0)), blockFlight, blockAirport);
        op.confirmarBloque(blockFlight, blockAirport);

        TelemetriaSimulacionService service = serviceSinDataset();
        List<OcupacionAlmacenSlot> serie =
                service.buildSerieAlmacenes(blockAirport, graph, op);

        long enOrigen = slotsQueCubren("07:00", "08:30");
        long enEscala = slotsQueCubren("09:30", "18:00");
        long enDestino = slotsQueCubren("19:00", "19:15");

        assertEquals(enOrigen + enEscala + enDestino, serie.size(),
                "slots de origen + escala + destino");
        assertEquals(enOrigen, slotsDe(serie, "AAA").size(), "origen: espera [07:00, 08:30)");
        assertEquals(enEscala, slotsDe(serie, "BBB").size(), "escala: [09:30, 18:00)");
        assertEquals(enDestino, slotsDe(serie, "CCC").size(), "destino: [19:00, 19:15)");
        assertTrue(serie.stream().anyMatch(s ->
                        s.getAeropuerto().equals("BBB") && s.getHora().startsWith(inicioDelSlotQueContiene("09:30"))),
                "las horas son el inicio del slot en eje UTC");

        // Realismo: cada slot del DTO coincide EXACTAMENTE con la ocupación global del modelo.
        for (OcupacionAlmacenSlot s : serie) {
            assertEquals(20, s.getOcupacion(), "B1 ocupa 20 maletas en " + s.getAeropuerto() + "@" + s.getHora());
            long slotMin = OperadorReparacionVoraz.aMinutoEpochPublico(LocalDateTime.parse(s.getHora()));
            long claveSlot = OperadorReparacionVoraz.claveAlmacenDeSlot(
                    graph.nodos.get(s.getAeropuerto()).indice, slotMin);
            assertEquals(op.ocupacionGlobalAlmacen(claveSlot), s.getOcupacion(),
                    "el DTO reporta lo mismo que valida el motor (slot " + s.getHora() + ")");
            assertEquals("VERDE", s.getSemaforo(), "20/500 = 4% ⇒ VERDE");
        }
    }

    /** La espera en ORIGEN de un envío sin ruta (backlog) también aparece en la serie. */
    @Test
    void laSerieIncluyeLaEsperaEnOrigenDeEnviosSinRutaDelBacklog() {
        Grafo graph = grafoEscalaLarga();
        OperadorReparacionVoraz op = new OperadorReparacionVoraz(graph);
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        enrutar(op, batch("B1", 20, LocalTime.of(7, 0)), blockFlight, blockAirport);
        op.confirmarBloque(blockFlight, blockAirport);

        // B2 (5 maletas, ready 07:30) queda SIN ruta; el reloj UTC avanza a 10:00 con B3.
        LoteEnvio b2 = batch("B2", 5, LocalTime.of(7, 30));
        LoteEnvio b3 = batch("B3", 1, LocalTime.of(10, 0));
        op.reconstruirEsperaOrigenBacklog(List.of(b2), List.of(b3));

        TelemetriaSimulacionService service = serviceSinDataset();
        List<OcupacionAlmacenSlot> serie =
                service.buildSerieAlmacenes(blockAirport, graph, op);

        // El slot 08:00 de AAA fue tocado por B1; su ocupación acumulada debe incluir a B2
        // (espera [07:30, 10:00) del backlog): 20 + 5 = 25.
        OcupacionAlmacenSlot slot8 = serie.stream()
                .filter(s -> s.getAeropuerto().equals("AAA") && s.getHora().startsWith("2026-01-01T08:00"))
                .findFirst().orElseThrow();
        assertEquals(25, slot8.getOcupacion(),
                "la serie incluye la espera en origen del backlog (20 de B1 + 5 de B2)");
    }

    // ----------------------------------------------------------------------- helpers

    private static TelemetriaSimulacionService serviceSinDataset() {
        return new TelemetriaSimulacionService();
    }

    private static List<OcupacionAlmacenSlot> slotsDe(
            List<OcupacionAlmacenSlot> serie, String aeropuerto) {
        return serie.stream().filter(s -> s.getAeropuerto().equals(aeropuerto)).toList();
    }

    /**
     * Cuántos slots distintos toca la estadía {@code [desde, hasta)}, contados minuto a minuto
     * (definición independiente de la aritmética del operador: un slot cuenta si alguno de los
     * minutos de la estadía cae dentro de él).
     */
    private static long slotsQueCubren(String desde, String hasta) {
        long g = OperadorReparacionVoraz.SLOT_ALMACEN_MIN;
        return LongStream.range(minutosDelDia(desde), minutosDelDia(hasta))
                .map(m -> m / g)
                .distinct()
                .count();
    }

    /** Prefijo ISO del slot que contiene la hora dada, p. ej. {@code 2026-01-01T09:30}. */
    private static String inicioDelSlotQueContiene(String hora) {
        long g = OperadorReparacionVoraz.SLOT_ALMACEN_MIN;
        long inicio = (minutosDelDia(hora) / g) * g;
        return LocalDateTime.of(DIA, LocalTime.MIDNIGHT).plusMinutes(inicio).toString();
    }

    private static long minutosDelDia(String hora) {
        LocalTime t = LocalTime.parse(hora);
        return t.getHour() * 60L + t.getMinute();
    }

    private static void enrutar(OperadorReparacionVoraz op, LoteEnvio b,
                                Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        RutaCandidata ruta = op.generarCandidatosRuta(b, blockFlight, blockAirport, 3).stream()
                .filter(RutaCandidata::isCumpleSLA)
                .findFirst().orElseThrow();
        op.aplicarCandidatoRuta(b, ruta);
        op.aplicarCandidatoBloque(b, ruta, blockFlight, blockAirport);
    }

    private static LoteEnvio batch(String id, int qty, LocalTime ready) {
        return new LoteEnvio(id, qty, 24, "AAA", "CCC", LocalDateTime.of(DIA, ready));
    }

    /** AAA→BBB (08:30-09:30) y BBB→CCC (18:00-19:00): escala de 8h30 en BBB, sin ruta directa. */
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
