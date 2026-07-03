package com.tasfb2b.planificador.algoritmo.aco;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;

import com.tasfb2b.planificador.algoritmo.alns.CodificadorClaveVuelo;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.dto.simulacion.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColoniaACOTest {

    @Test
    void dijkstraHijoGeneraCandidatosSinAplicarCapacidad() {
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        LoteEnvio batch = batch("B1", 10, "AAA", "CCC", 24);

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        List<RutaCandidata> candidatos = enrutador.generarCandidatosRuta(batch, blockFlight, blockAirport, 3);

        assertTrue(candidatos.size() >= 2);
        assertTrue(candidatos.get(0).isCumpleSLA());
        assertTrue(blockFlight.isEmpty(), "Dijkstra hijo solo propone rutas, no confirma capacidad");
        assertTrue(batch.getRutaAsignada().isEmpty(), "Dijkstra hijo no muta el batch al generar candidatos");
    }

    @Test
    void rutaCacheadaSeRevalidaYSeRechazaSiQuedoSaturada() {
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        LoteEnvio blocker = batch("B0", 25, "AAA", "CCC", 24);
        LoteEnvio batch = batch("B1", 20, "AAA", "CCC", 24);

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        RutaCandidata direct = enrutador.generarCandidatosRuta(blocker, blockFlight, blockAirport, 3).stream()
                .filter(c -> c.getAristas().size() == 1)
                .findFirst()
                .orElseThrow();
        enrutador.aplicarCandidatoBloque(blocker, direct, blockFlight, blockAirport);
        for (int day = 2; day <= 3; day++) {
            LoteEnvio nextBlocker = batchAt("B0D" + day, 25, "AAA", "CCC", 24, day);
            RutaCandidata nextDirect = enrutador.materializarRutaCandidata(
                    nextBlocker, direct.getAristas(), blockFlight, blockAirport);
            assertNotNull(nextDirect);
            enrutador.aplicarCandidatoBloque(nextBlocker, nextDirect, blockFlight, blockAirport);
        }

        assertNull(enrutador.materializarRutaCandidata(batch, direct.getAristas(), blockFlight, blockAirport),
                "La cache solo guarda esqueletos; la capacidad vigente debe revalidarse");

        List<RutaCandidata> alternativas = enrutador.generarCandidatosRuta(batch, blockFlight, blockAirport, 3);
        assertFalse(alternativas.isEmpty(), "Dijkstra hijo debe buscar alternativa cuando la cache ya no sirve");
        assertTrue(alternativas.stream().anyMatch(c -> c.getAristas().size() > 1));
    }

    @Test
    void acoPadreAsignaElBloqueUsandoCandidatosDijkstra() {
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        ColoniaACO engine = new ColoniaACO(new PlanificadorProperties());

        List<LoteEnvio> batches = List.of(
                batch("B1", 20, "AAA", "CCC", 24),
                batch("B2", 20, "AAA", "CCC", 24)
        );
        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        int enrutados = engine.procesar(graph, enrutador, batches, blockFlight, blockAirport, new Random(7L), 1_000L);

        assertEquals(2, enrutados);
        assertFalse(blockFlight.isEmpty(), "ACO padre confirma la asignacion sobre el bloque");
        assertTrue(batches.stream().allMatch(b -> b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty()));
        assertTrue(batches.stream().allMatch(LoteEnvio::isCumpleSLA));
    }

    @Test
    void acoNoSobrepasaCapacidadAunConMemoizacionDeFrontier() {
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        ColoniaACO engine = new ColoniaACO(new PlanificadorProperties());

        // Varios batches del mismo OD (AAA->CCC) ejercitan el camino de
        // memoizacion del frontier (Fase B) y saturan el vuelo directo F1
        // (cap 25), obligando a repartir por las rutas alternativas de 2 tramos.
        List<LoteEnvio> batches = List.of(
                batch("B1", 20, "AAA", "CCC", 24),
                batch("B2", 20, "AAA", "CCC", 24),
                batch("B3", 20, "AAA", "CCC", 24),
                batch("B4", 20, "AAA", "CCC", 24));

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        int enrutados = engine.procesar(graph, enrutador, batches, blockFlight, blockAirport,
                new Random(11L), 2_000L);

        assertEquals(4, enrutados, "Las rutas alternativas permiten enrutar los 4 batches");
        // Invariante de correctitud de la Fase B: la invalidacion por interseccion
        // de claves garantiza que ninguna asignacion reusada sobrepase capacidad.
        Map<Integer, Arista> porIdx = new HashMap<>();
        for (Arista e : graph.aristas) porIdx.put(e.indice, e);
        for (Map.Entry<Long, Integer> entry : blockFlight.entrySet()) {
            int edgeIdx = (int) (entry.getKey() >> CodificadorClaveVuelo.BITS_DIA);
            Arista edge = porIdx.get(edgeIdx);
            assertNotNull(edge, "claveVuelo decodifica a una arista valida");
            assertTrue(entry.getValue() <= edge.capacidad,
                    "vuelo " + edge.id + " sobre capacidad: " + entry.getValue() + "/" + edge.capacidad);
        }
    }

    @Test
    void ordenarPorUrgenciaPriorizaFechaLimiteAbsolutaNoHorasDeSla() {
        ColoniaACO engine = new ColoniaACO(new PlanificadorProperties());

        // "viejo": readyTime antiguo (día 1) con SLA largo (48h) → deadline día 3 07:00.
        // "nuevo": readyTime reciente (día 3) con SLA corto (24h) → deadline día 4 07:00.
        // Por horas de SLA, "nuevo" (24h) iría primero; por DEADLINE absoluto debe ir
        // primero "viejo", porque su vencimiento está más cerca (anti-inanición, G1).
        LoteEnvio viejo = batchAt("VIEJO", 10, "AAA", "CCC", 48, 1);
        LoteEnvio nuevo = batchAt("NUEVO", 10, "AAA", "CCC", 24, 3);

        List<LoteEnvio> ordenado = engine.ordenarPorUrgencia(Arrays.asList(nuevo, viejo));

        assertEquals("VIEJO", ordenado.get(0).getId(),
                "el envío con deadline absoluto más cercano va primero");
        assertEquals("NUEVO", ordenado.get(1).getId());
    }

    @Test
    void urgenteTomaElVueloEscasoYElFlexibleSeDesvia() {
        // Fase J: con un vuelo directo escaso (F1, cap 25) y dos envíos AAA->CCC de qty 20
        // (no caben ambos en F1), el URGENTE (SLA 4h: solo el directo llega on-time) debe
        // quedarse con F1 y el FLEXIBLE (SLA 24h) debe ceder y desviarse por una ruta de
        // 2 tramos. Demuestra que la capacidad escasa se reserva para quien la necesita.
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        ColoniaACO engine = new ColoniaACO(new PlanificadorProperties());

        LoteEnvio urgente = batch("U", 20, "AAA", "CCC", 4);
        LoteEnvio flexible = batch("F", 20, "AAA", "CCC", 24);

        Map<Long, Integer> blockFlight = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();
        int enrutados = engine.procesar(graph, enrutador, Arrays.asList(flexible, urgente),
                blockFlight, blockAirport, new Random(5L), 2_000L);

        assertEquals(2, enrutados, "ambos se enrutan on-time");
        assertTrue(urgente.isCumpleSLA() && flexible.isCumpleSLA());
        assertEquals(1, urgente.getRutaAsignada().size(),
                "el urgente toma el vuelo directo escaso (1 tramo)");
        assertTrue(flexible.getRutaAsignada().size() >= 2,
                "el flexible cede el vuelo escaso y se desvía (>=2 tramos)");
    }

    @Test
    void reservaAlmacenHubBloqueaFlexiblePeroSegundaPasadaLaRecupera() {
        // Fase L2: con la estadía de escala en un HUB de almacén casi lleno, un envío
        // FLEXIBLE (48h, mucha holgura) debe ser rechazado por la reserva de colchón
        // (1.ª pasada reservaAlmacen>0), pero la 2.ª pasada (reservaAlmacen=0) le DEBE
        // devolver la misma ruta: la reserva nunca causa un sinRuta evitable (anti-J3/K1).
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        // Los nodos de tránsito del grafo de prueba (BBB, DDD) se designan hub:
        // así cualquier candidato multi-tramo transita un hub de almacén.
        enrutador.setHubs(Set.of("BBB", "DDD", "CCC"));

        LoteEnvio flexible = batch("F48", 20, "AAA", "CCC", 48);

        // Candidato de 2 tramos (transita un hub). El Dijkstra hijo no aplica reserva.
        RutaCandidata viaHub = enrutador
                .generarCandidatosRuta(flexible, new HashMap<>(), new HashMap<>(), 4).stream()
                .filter(c -> c.getAristas().size() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("se esperaba una ruta multi-tramo via hub"));

        // Saturar el almacén-día de la escala (nodo de tránsito) dejando un remanente
        // > qty (pasa el chequeo base) pero menor que qty + colchón (falla la reserva).
        Arista escala = viaHub.getAristas().get(0);
        long llegadaEscala = viaHub.getSalidasReales().get(0) + escala.duracionMinutos;
        long claveEscala = OperadorReparacionVoraz.claveAlmacenDeSlot(escala.destino.indice, llegadaEscala);
        int remanente = 22;   // qty=20 < 22 ; colchón (≈10% de 500 escalado por holgura) ≫ 2
        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveEscala, escala.destino.capacidad - remanente);

        assertFalse(
                enrutador.rutaSirveParaLote(viaHub, flexible, new HashMap<>(), blockAirport, 0.0, 0.10),
                "con reserva de almacén el flexible no cabe en el hub casi lleno");
        assertTrue(
                enrutador.rutaSirveParaLote(viaHub, flexible, new HashMap<>(), blockAirport, 0.0, 0.0),
                "la 2.ª pasada (reserva=0) recupera la ruta: nunca un sinRuta evitable");
    }

    @Test
    void reservaAlmacenNoPenalizaCuandoElDestinoFinalEsHub() {
        // Fase L2 (alcance): la reserva de almacén-hub protege el TRÁNSITO overnight,
        // no a quien TERMINA en el hub. Un envío cuyo destino final es hub no sufre la
        // reserva aunque ese almacén-día esté casi lleno (la entrega no es desviable).
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        enrutador.setHubs(Set.of("CCC"));   // el destino final CCC es hub

        LoteEnvio flexible = batch("F48", 20, "AAA", "CCC", 48);

        RutaCandidata directo = enrutador
                .generarCandidatosRuta(flexible, new HashMap<>(), new HashMap<>(), 4).stream()
                .filter(c -> c.getAristas().size() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("se esperaba la ruta directa AAA->CCC"));

        Arista ultimo = directo.getAristas().get(0);
        long llegadaDestino = directo.getSalidasReales().get(0) + ultimo.duracionMinutos;
        long claveDestino = OperadorReparacionVoraz.claveAlmacenDeSlot(ultimo.destino.indice, llegadaDestino);
        Map<Long, Integer> blockAirport = new HashMap<>();
        blockAirport.put(claveDestino, ultimo.destino.capacidad - 22);   // casi lleno, remanente 22 >= qty

        assertTrue(
                enrutador.rutaSirveParaLote(directo, flexible, new HashMap<>(), blockAirport, 0.0, 0.10),
                "el destino final hub NO lleva colchón de reserva: la entrega no se penaliza");
    }

    @Test
    void almacenEsOcupacionConcurrentePorSlotNoCupoDiario() {
        // Fase R: el almacén modela OCUPACIÓN CONCURRENTE (maletas presentes a la vez ≤ capacidad),
        // NO un tope de throughput por día. Una franja horaria distinta a la de llegada, aunque esté
        // llena, NO afecta a la maleta (no coinciden en el tiempo); solo el SLOT de su estadía importa.
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);

        LoteEnvio flexible = batch("F48", 20, "AAA", "CCC", 48);
        RutaCandidata directo = enrutador
                .generarCandidatosRuta(flexible, new HashMap<>(), new HashMap<>(), 4).stream()
                .filter(c -> c.getAristas().size() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("se esperaba la ruta directa AAA->CCC"));

        Arista ultimo = directo.getAristas().get(0);
        long llegada = directo.getSalidasReales().get(0) + ultimo.duracionMinutos;
        int cap = ultimo.destino.capacidad;

        // (a) OTRA franja (5 h después) saturada → NO bloquea: el almacén es concurrente por slot.
        Map<Long, Integer> otraFranjaLlena = new HashMap<>();
        otraFranjaLlena.put(OperadorReparacionVoraz.claveAlmacenDeSlot(ultimo.destino.indice, llegada + 5 * 60), cap);
        assertTrue(
                enrutador.rutaSirveParaLote(directo, flexible, new HashMap<>(), otraFranjaLlena, 0.0, 0.0),
                "una franja horaria distinta llena no afecta: NO es un cupo diario");

        // (b) El SLOT de llegada saturado → SÍ bloquea: no cabe la maleta concurrentemente.
        Map<Long, Integer> slotLlegadaLleno = new HashMap<>();
        slotLlegadaLleno.put(OperadorReparacionVoraz.claveAlmacenDeSlot(ultimo.destino.indice, llegada), cap);
        assertFalse(
                enrutador.rutaSirveParaLote(directo, flexible, new HashMap<>(), slotLlegadaLleno, 0.0, 0.0),
                "el slot de la estadía lleno sí bloquea (ocupación concurrente > capacidad)");
    }

    @Test
    void reclasificarHubsMarcaAeropuertoCalienteYActivaLaReserva() {
        // Fase O: un aeropuerto que NO está en la lista estática de hubs pero cuya ocupación-pico
        // de almacén supera el umbral debe pasar a tratarse como hub (la reserva L2 empieza a
        // protegerlo). Verifica el descubrimiento dinámico vía el comportamiento de rutaSirveParaLote.
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        // NO llamamos setHubs: el operador arranca SIN hubs (no hay lista hardcodeada); los hubs
        // solo se descubren por utilización real vía reclasificarHubsPorUtilizacion().

        LoteEnvio flexible = batch("F48", 20, "AAA", "CCC", 48);
        RutaCandidata viaTransito = enrutador
                .generarCandidatosRuta(flexible, new HashMap<>(), new HashMap<>(), 4).stream()
                .filter(c -> c.getAristas().size() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("se esperaba una ruta multi-tramo"));

        Arista escala = viaTransito.getAristas().get(0);
        long llegadaEscala = viaTransito.getSalidasReales().get(0) + escala.duracionMinutos;
        long claveEscala = OperadorReparacionVoraz.claveAlmacenDeSlot(escala.destino.indice, llegadaEscala);

        // Antes de reclasificar: el nodo de tránsito NO es hub → la reserva NO aplica aunque el
        // almacén-día esté casi lleno; la ruta pasa (remanente 22 >= qty 20).
        Map<Long, Integer> sembrado = new HashMap<>();
        sembrado.put(claveEscala, escala.destino.capacidad - 22);   // ocupación-pico 478/500 = 0.956
        enrutador.confirmarBloque(new HashMap<>(), sembrado);     // -> ocupacionAeropuerto global

        assertTrue(
                enrutador.rutaSirveParaLote(viaTransito, flexible, new HashMap<>(), new HashMap<>(), 0.0, 0.10),
                "antes de reclasificar el nodo no es hub: la reserva no aplica");

        // Reclasificar: el nodo de tránsito supera 0.65 de utilización-pico → pasa a hub.
        enrutador.reclasificarHubsPorUtilizacion(0.65);

        assertFalse(
                enrutador.rutaSirveParaLote(viaTransito, flexible, new HashMap<>(), new HashMap<>(), 0.0, 0.10),
                "tras reclasificar el nodo caliente es hub: la reserva bloquea al flexible");
        assertTrue(
                enrutador.rutaSirveParaLote(viaTransito, flexible, new HashMap<>(), new HashMap<>(), 0.0, 0.0),
                "la 2.ª pasada (reserva=0) recupera la ruta: nunca un sinRuta evitable");
    }

    @Test
    void configurarStorageAwarePropagaElUmbralALaReclasificacionAutomatica() {
        // Fase P: configurarStorageAware fija el umbral que usa la reclasificación dinámica DENTRO
        // de confirmarBloque. Un nodo a pico ~0.96: con umbral 0.55 pasa a hub (la reserva L2 lo protege
        // → rutaSirveParaLote RECHAZA al flexible); con umbral 0.99 NO llega a hub (sin reserva →
        // pasa). Demuestra que el valor configurado llega al camino real de clasificación.
        assertFalse(rutaSirveTrasSembrarHub(0.55),
                "con umbral 0.55 el nodo caliente es hub: la reserva bloquea al flexible");
        assertTrue(rutaSirveTrasSembrarHub(0.99),
                "con umbral 0.99 el nodo no llega a hub: la reserva no aplica");
    }

    /**
     * Configura el umbral, siembra el almacén-día de una escala a pico ~0.96 y dispara la
     * reclasificación automática de {@code confirmarBloque} (cada 10 commits). Devuelve si la ruta
     * via-escala sirve para un 48h flexible con reserva de almacén 0.10.
     */
    private static boolean rutaSirveTrasSembrarHub(double umbralHubPico) {
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        enrutador.configurarStorageAware(umbralHubPico, 1.7);

        LoteEnvio flexible = batch("F48", 20, "AAA", "CCC", 48);
        RutaCandidata viaTransito = enrutador
                .generarCandidatosRuta(flexible, new HashMap<>(), new HashMap<>(), 4).stream()
                .filter(c -> c.getAristas().size() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("se esperaba una ruta multi-tramo"));

        Arista escala = viaTransito.getAristas().get(0);
        long llegada = viaTransito.getSalidasReales().get(0) + escala.duracionMinutos;
        long clave = OperadorReparacionVoraz.claveAlmacenDeSlot(escala.destino.indice, llegada);

        // Pico ~0.96 (remanente 22) en el almacén-slot de la escala, commiteado a la ocupación global.
        Map<Long, Integer> seed = new HashMap<>();
        seed.put(clave, escala.destino.capacidad - 22);
        enrutador.confirmarBloque(new HashMap<>(), seed);
        // 9 commits vacíos más → el 10.º dispara reclasificarHubsPorUtilizacion(umbralHubPico).
        for (int i = 0; i < 9; i++) enrutador.confirmarBloque(new HashMap<>(), new HashMap<>());

        return enrutador.rutaSirveParaLote(viaTransito, flexible, new HashMap<>(), new HashMap<>(), 0.0, 0.10);
    }

    @Test
    void acoNoConfirmaRutasTardiasLasDifiere() {
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        ColoniaACO engine = new ColoniaACO(new PlanificadorProperties());

        // SLA de 1h es imposible: la ruta directa AAA->CCC ya llega ~3h despues
        // del readyTime. Por la Politica 1 (F1) el motor NO confirma rutas tardias:
        // el batch queda sinRuta para diferirse al backlog, no como tardada.
        LoteEnvio batch = batch("B1", 10, "AAA", "CCC", 1);

        int enrutados = engine.procesar(graph, enrutador, List.of(batch),
                new HashMap<>(), new HashMap<>(), new Random(3L), 1_000L);

        assertEquals(0, enrutados, "una ruta tardia no se confirma: se difiere");
        assertTrue(batch.getRutaAsignada() == null || batch.getRutaAsignada().isEmpty(),
                "el batch tardio queda sin ruta asignada");
        assertFalse(batch.isCumpleSLA());
    }

    @Test
    void acoPadreConTaAgotadoNoRompeYDejaRestantesSinRuta() {
        Grafo graph = graphConRutasAlternativas();
        OperadorReparacionVoraz enrutador = new OperadorReparacionVoraz(graph);
        ColoniaACO engine = new ColoniaACO(new PlanificadorProperties());

        List<LoteEnvio> batches = List.of(
                batch("B1", 20, "AAA", "CCC", 24),
                batch("B2", 20, "AAA", "CCC", 24),
                batch("B3", 20, "AAA", "CCC", 24)
        );

        int enrutados = engine.procesar(graph, enrutador, batches, new HashMap<>(), new HashMap<>(), new Random(7L), 1L);

        assertTrue(enrutados >= 0 && enrutados <= batches.size());
        assertTrue(batches.stream()
                .filter(b -> b.getRutaAsignada() == null || b.getRutaAsignada().isEmpty())
                .noneMatch(LoteEnvio::isCumpleSLA));
    }

    @Test
    void contratoSimulacionResponseMantieneCamposPrincipales() {
        assertFields(SimulacionResponse.class,
                "metricas", "totalBloques", "vuelosPlaneados", "aeropuertosInfo", "k", "saMinutos");
        assertFields(Metricas.class,
                "procesadas", "enrutadas", "sinRuta", "cumpleSLA", "tardadas", "maletasIndividuales");
        assertFields(BloqueSimulacion.class,
                "horaInicio", "horaFin", "asignaciones", "cargasVuelos", "ocupacionAlmacenes", "taMs");
        assertFields(AsignacionMaleta.class,
                "batchId", "origen", "destino", "cantidad", "enrutada", "cumpleSLA", "rutaVuelos", "tramos");
    }

    private static LoteEnvio batch(String id, int qty, String origen, String destino, int slaHours) {
        return batchAt(id, qty, origen, destino, slaHours, 1);
    }

    private static LoteEnvio batchAt(String id, int qty, String origen, String destino, int slaHours, int day) {
        return new LoteEnvio(id, qty, slaHours, origen, destino,
                LocalDateTime.of(LocalDate.of(2026, 1, day), LocalTime.of(7, 0)));
    }

    private static Grafo graphConRutasAlternativas() {
        Grafo graph = new Grafo();
        Nodo aaa = node("AAA");
        Nodo bbb = node("BBB");
        Nodo ddd = node("DDD");
        Nodo ccc = node("CCC");
        graph.nodos.put(aaa.codigo, aaa);
        graph.nodos.put(bbb.codigo, bbb);
        graph.nodos.put(ddd.codigo, ddd);
        graph.nodos.put(ccc.codigo, ccc);

        int idx = 0;
        agregarArista(graph, idx++, aaa, ccc, "F1", "08:00", "10:00", 25);
        agregarArista(graph, idx++, aaa, bbb, "F2", "08:30", "09:30", 50);
        agregarArista(graph, idx++, bbb, ccc, "F3", "10:00", "11:00", 50);
        agregarArista(graph, idx++, aaa, ddd, "F4", "09:00", "10:00", 50);
        agregarArista(graph, idx, ddd, ccc, "F5", "10:30", "11:30", 50);
        return graph;
    }

    private static Nodo node(String code) {
        Nodo node = new Nodo(code);
        node.capacidad = 500;
        return node;
    }

    private static void agregarArista(Grafo graph, int idx, Nodo from, Nodo to, String id,
                                String dep, String arr, int cap) {
        Arista edge = new Arista();
        edge.indice = idx;
        edge.id = id;
        edge.origen = from;
        edge.destino = to;
        edge.capacidad = cap;
        edge.horaSalida = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.parse(dep));
        edge.horaLlegada = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.parse(arr));
        edge.horaSalidaLocal = edge.horaSalida.toLocalTime();
        edge.minutoDelDiaSalida = edge.horaSalidaLocal.getHour() * 60 + edge.horaSalidaLocal.getMinute();
        edge.duracionMinutos = (int) java.time.Duration.between(edge.horaSalida, edge.horaLlegada).toMinutes();
        edge.costo = edge.duracionMinutos;
        graph.agregarArista(edge);
    }

    private static void assertFields(Class<?> type, String... expected) {
        Set<String> actual = Arrays.stream(type.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
        for (String field : expected) {
            assertTrue(actual.contains(field), type.getSimpleName() + " conserva campo " + field);
        }
    }
}
