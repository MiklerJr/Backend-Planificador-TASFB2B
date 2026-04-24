package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.algorithm.alns.*;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.Vuelo;
import com.tasfb2b.planificador.util.AlgorithmMapper;
import com.tasfb2b.planificador.util.DataLoader;
import com.tasfb2b.planificador.util.FlightCancellationSimulator;
import com.tasfb2b.planificador.util.FlightParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlanificadorService {

    private static final int    ALNS_MAX_ITERATIONS = 9;
    private static final int    SA_MINUTOS          = 10;

    // Umbral de sinRuta por ventana para declarar colapso (escenario 3)
    private static final double UMBRAL_COLAPSO_DEFAULT = 0.20;

    private final DataLoader     dataLoader;
    private final AlgorithmMapper mapper;

    // ── Caché escenario 2 (período) ─────────────────────────────────────────
    private volatile List<SimulacionResponse.BloqueSimulacion> bloquesCacheados = null;

    // ── Estado escenario 1 (día a día) ───────────────────────────────────────
    private volatile Graph                                     sc1Graph      = null;
    private volatile GreedyRepairOperator                      sc1Enrutador  = null;
    private volatile AlnsSolution                              sc1Dummy      = null;
    private volatile List<LocalDateTime>                       sc1Ventanas   = null;
    private volatile int                                       sc1Idx        = 0;
    private volatile int                                       sc1Envios     = 0;
    private volatile int                                       sc1Enrutadas  = 0;
    private volatile int                                       sc1SinRuta    = 0;
    private volatile int                                       sc1CumpleSLA  = 0;
    private volatile int                                       sc1Tardadas   = 0;
    private volatile long                                      sc1Maletas    = 0L;
    private volatile List<SimulacionResponse.BloqueSimulacion> sc1Bloques    = new ArrayList<>();
    private final    Map<String, int[]>                        sc1OdStats    = new HashMap<>();

    public PlanificadorService(DataLoader dataLoader, AlgorithmMapper mapper) {
        this.dataLoader = dataLoader;
        this.mapper     = mapper;
    }

    // =========================================================
    // Escenario 2: Simulación de período (batch completo)
    // =========================================================
    public SimulacionResponse ejecutarALNS(int k, double cancelProb) {
        log.info("Escenario 2 — ALNS ({} iters/bloque, K={}, Sa={}min, cancelProb={}%) ...",
                ALNS_MAX_ITERATIONS, k, SA_MINUTOS, String.format("%.1f", cancelProb * 100));
        long inicio = System.currentTimeMillis();

        if (dataLoader.getVentanas().isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k); r.setSaMinutos(SA_MINUTOS);
            return r;
        }

        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AlnsSolution solucionDummy     = new AlnsSolution(Collections.emptyList());

        SortedSet<LocalDateTime> ventanas = (SortedSet<LocalDateTime>) dataLoader.getVentanas();
        int totalVentanas    = ventanas.size();
        int intervaloReporte = Math.max(1, totalVentanas / 10);

        int totalVuelosCancelados = 0;
        if (cancelProb > 0.0) {
            long startDay = ventanas.first().toLocalDate().toEpochDay();
            long endDay   = ventanas.last().toLocalDate().toEpochDay() + 3;
            Set<Long> cancelados = FlightCancellationSimulator.generate(
                    graph.edges, startDay, endDay, cancelProb);
            enrutador.setCancelledFlights(cancelados);
            totalVuelosCancelados = cancelados.size();
            log.info("Cancelaciones: {} vuelo-días", totalVuelosCancelados);
        }

        List<SimulacionResponse.BloqueSimulacion> bloques = new ArrayList<>(totalVentanas);
        Map<String, int[]> odStats = new HashMap<>();
        int  totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
             totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;

        for (LocalDateTime vi : ventanas) {
            bloqueActual++;
            ResultadoVentana rv = procesarVentana(vi, graph, enrutador, solucionDummy, odStats);
            bloques.add(rv.bloque);

            totalEnvios    += rv.envios;
            totalEnrutadas += rv.enrutadas;
            totalSinRuta   += rv.sinRuta;
            totalCumpleSLA += rv.cumpleSLA;
            totalTardadas  += rv.tardadas;
            totalMaletas   += rv.maletas;

            if (bloqueActual % intervaloReporte == 0 || bloqueActual == totalVentanas) {
                log.info("Progreso E2: {}% — {}/{} | envíos:{} maletas:{} | ok:{} tarde:{} sinRuta:{}",
                        (int) Math.round(bloqueActual * 100.0 / totalVentanas),
                        bloqueActual, totalVentanas,
                        totalEnvios, totalMaletas,
                        totalCumpleSLA, totalTardadas, totalSinRuta);
            }
        }

        bloquesCacheados = bloques;
        long tiempoMs = System.currentTimeMillis() - inicio;
        log.info("E2 completado en {} ms — {} bloques | {} envíos | {} maletas | ok:{} tarde:{} sinRuta:{}",
                tiempoMs, bloques.size(), totalEnvios, totalMaletas,
                totalCumpleSLA, totalTardadas, totalSinRuta);
        logDiagnosticos(odStats, graph, enrutador);

        SimulacionResponse res = construirRespuestaFront(0, tiempoMs,
                dataLoader.getVuelos(), bloques.size(), ventanas.first().toLocalDate());
        llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, totalVuelosCancelados, false, -1);
        res.setK(k); res.setSaMinutos(SA_MINUTOS);
        return res;
    }

    public SimulacionResponse.BloqueSimulacion getBloque(int index) {
        if (bloquesCacheados == null || index < 0 || index >= bloquesCacheados.size()) return null;
        return bloquesCacheados.get(index);
    }

    // =========================================================
    // Escenario 1: Día a día (ventana por ventana, con estado)
    // =========================================================

    /** Inicializa el estado del escenario 1. Debe llamarse antes de procesarSiguienteVentana(). */
    public synchronized Map<String, Object> inicializarEscenario1(double cancelProb) {
        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb));
        log.info("Escenario 1 — inicializando (cancelProb={}%) ...",
                String.format("%.1f", cancelProb * 100));

        sc1Graph     = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        sc1Enrutador = new GreedyRepairOperator(sc1Graph);
        sc1Dummy     = new AlnsSolution(Collections.emptyList());
        sc1Idx       = 0;
        sc1Envios    = sc1Enrutadas = sc1SinRuta = sc1CumpleSLA = sc1Tardadas = 0;
        sc1Maletas   = 0L;
        sc1Bloques   = new ArrayList<>();
        sc1OdStats.clear();

        SortedSet<LocalDateTime> ventanas = (SortedSet<LocalDateTime>) dataLoader.getVentanas();
        sc1Ventanas = new ArrayList<>(ventanas);

        if (cancelProb > 0.0 && !sc1Ventanas.isEmpty()) {
            long startDay = sc1Ventanas.get(0).toLocalDate().toEpochDay();
            long endDay   = sc1Ventanas.get(sc1Ventanas.size() - 1).toLocalDate().toEpochDay() + 3;
            Set<Long> cancelados = FlightCancellationSimulator.generate(
                    sc1Graph.edges, startDay, endDay, cancelProb);
            sc1Enrutador.setCancelledFlights(cancelados);
            log.info("E1 listo: {} ventanas, {} cancelaciones", sc1Ventanas.size(), cancelados.size());
        } else {
            log.info("E1 listo: {} ventanas, sin cancelaciones", sc1Ventanas.size());
        }

        return Map.of(
            "estado",        "inicializado",
            "totalVentanas", sc1Ventanas.size(),
            "ventanaActual", 0
        );
    }

    /**
     * Procesa la siguiente ventana del escenario 1 y devuelve su bloque.
     * Devuelve null cuando todas las ventanas han sido procesadas.
     * Lanza IllegalStateException si no se ha llamado a inicializarEscenario1() antes.
     */
    public synchronized SimulacionResponse.BloqueSimulacion procesarSiguienteVentana() {
        if (sc1Graph == null)
            throw new IllegalStateException("Escenario 1 no inicializado — llame a /escenario1/inicializar primero");
        if (sc1Idx >= sc1Ventanas.size()) {
            log.info("E1 completo: todas las ventanas procesadas");
            return null;
        }

        LocalDateTime vi = sc1Ventanas.get(sc1Idx);
        sc1Idx++;

        ResultadoVentana rv = procesarVentana(vi, sc1Graph, sc1Enrutador, sc1Dummy, sc1OdStats);
        sc1Bloques.add(rv.bloque);

        sc1Envios    += rv.envios;
        sc1Enrutadas += rv.enrutadas;
        sc1SinRuta   += rv.sinRuta;
        sc1CumpleSLA += rv.cumpleSLA;
        sc1Tardadas  += rv.tardadas;
        sc1Maletas   += rv.maletas;

        log.info("E1 ventana {}/{}: envíos:{} | enrutados:{} | tardados:{} | sinRuta:{}",
                sc1Idx, sc1Ventanas.size(),
                rv.envios, rv.enrutadas, rv.tardadas, rv.sinRuta);

        // Al terminar todas las ventanas, emitir diagnóstico
        if (sc1Idx == sc1Ventanas.size()) {
            log.info("E1 finalizado — {} ventanas | {} envíos | {} maletas | ok:{} tarde:{} sinRuta:{}",
                    sc1Ventanas.size(), sc1Envios, sc1Maletas,
                    sc1CumpleSLA, sc1Tardadas, sc1SinRuta);
            logDiagnosticos(sc1OdStats, sc1Graph, sc1Enrutador);
        }

        return rv.bloque;
    }

    /** Devuelve el estado actual del escenario 1 sin avanzar la ventana. */
    public Map<String, Object> getEstadoEscenario1() {
        return Map.of(
            "inicializado",  sc1Graph != null,
            "ventanaActual", sc1Idx,
            "totalVentanas", sc1Ventanas != null ? sc1Ventanas.size() : 0,
            "totalEnvios",   sc1Envios,
            "totalEnrutadas",sc1Enrutadas,
            "totalSinRuta",  sc1SinRuta,
            "totalCumpleSLA",sc1CumpleSLA,
            "totalTardadas", sc1Tardadas,
            "totalMaletas",  sc1Maletas
        );
    }

    /** Devuelve un bloque ya procesado por índice (escenario 1). */
    public SimulacionResponse.BloqueSimulacion getBloqueEsc1(int index) {
        if (sc1Bloques == null || index < 0 || index >= sc1Bloques.size()) return null;
        return sc1Bloques.get(index);
    }

    // =========================================================
    // Escenario 3: Simulación hasta el colapso
    // =========================================================

    /**
     * Ejecuta el algoritmo con cancelaciones y se detiene cuando la tasa de
     * envíos sin ruta en una ventana supera umbralColapso.
     */
    public SimulacionResponse ejecutarHastaColapso(int k, double cancelProb, double umbralColapso) {
        cancelProb    = Math.max(0.0, Math.min(1.0, cancelProb));
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        log.info("Escenario 3 — colapso (K={}, cancelProb={}%, umbral={}%) ...",
                k, String.format("%.1f", cancelProb * 100),
                String.format("%.1f", umbralColapso * 100));
        long inicio = System.currentTimeMillis();

        if (dataLoader.getVentanas().isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
            r.setK(k); r.setSaMinutos(SA_MINUTOS);
            return r;
        }

        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AlnsSolution solucionDummy     = new AlnsSolution(Collections.emptyList());

        SortedSet<LocalDateTime> ventanas = (SortedSet<LocalDateTime>) dataLoader.getVentanas();

        if (cancelProb > 0.0) {
            long startDay = ventanas.first().toLocalDate().toEpochDay();
            long endDay   = ventanas.last().toLocalDate().toEpochDay() + 3;
            Set<Long> cancelados = FlightCancellationSimulator.generate(
                    graph.edges, startDay, endDay, cancelProb);
            enrutador.setCancelledFlights(cancelados);
            log.info("E3 cancelaciones: {} vuelo-días", cancelados.size());
        }

        List<SimulacionResponse.BloqueSimulacion> bloques = new ArrayList<>();
        Map<String, int[]> odStats = new HashMap<>();
        int  totalEnvios = 0, totalEnrutadas = 0, totalSinRuta = 0,
             totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;
        long totalMaletas = 0L;
        boolean collapsoDetectado = false;
        int     bloqueColapso     = -1;

        for (LocalDateTime vi : ventanas) {
            bloqueActual++;
            ResultadoVentana rv = procesarVentana(vi, graph, enrutador, solucionDummy, odStats);
            bloques.add(rv.bloque);

            totalEnvios    += rv.envios;
            totalEnrutadas += rv.enrutadas;
            totalSinRuta   += rv.sinRuta;
            totalCumpleSLA += rv.cumpleSLA;
            totalTardadas  += rv.tardadas;
            totalMaletas   += rv.maletas;

            // Detectar colapso: tasa de sinRuta de esta ventana supera el umbral
            // (mínimo 5 envíos en la ventana para evitar falsos positivos)
            if (rv.envios >= 5 && (double) rv.sinRuta / rv.envios >= umbralColapso) {
                collapsoDetectado = true;
                bloqueColapso     = bloqueActual;
                log.warn("COLAPSO en bloque {} — sinRuta:{}/{} ({}%)",
                        bloqueActual, rv.sinRuta, rv.envios,
                        String.format("%.0f", rv.sinRuta * 100.0 / rv.envios));
                break;
            }
        }

        bloquesCacheados = bloques;
        long tiempoMs = System.currentTimeMillis() - inicio;
        log.info("E3 {}: {} bloques | {} envíos | {} maletas | ok:{} tarde:{} sinRuta:{} | {} ms",
                collapsoDetectado ? "COLAPSÓ en bloque " + bloqueColapso : "sin colapso",
                bloques.size(), totalEnvios, totalMaletas,
                totalCumpleSLA, totalTardadas, totalSinRuta, tiempoMs);

        SimulacionResponse res = construirRespuestaFront(0, tiempoMs,
                dataLoader.getVuelos(), bloques.size(), ventanas.first().toLocalDate());
        llenarMetricas(res.getMetricas(), totalEnvios, totalEnrutadas, totalSinRuta,
                totalCumpleSLA, totalTardadas, totalMaletas, 0, collapsoDetectado, bloqueColapso);
        res.setK(k); res.setSaMinutos(SA_MINUTOS);
        return res;
    }

    // =========================================================
    // ACO
    // =========================================================
    public SimulacionResponse ejecutarACO() {
        log.info("Ejecutando ACO...");
        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        List<LuggageBatch> batches = mapper.mapToBatches(dataLoader.getMaletasMuestra(100));
        batches.sort(Comparator.comparing(LuggageBatch::getReadyTime));

        ConfigACO config = new ConfigACO();
        config.antCount   = 20;
        config.iterations = 100;
        new AlgorithmACO(graph, config);

        return construirRespuestaFront(0, 0L, dataLoader.getVuelos(), 0, null);
    }

    // =========================================================
    // Núcleo: procesa una sola ventana (compartido por los 3 escenarios)
    // =========================================================
    private ResultadoVentana procesarVentana(LocalDateTime vi,
                                             Graph graph,
                                             GreedyRepairOperator enrutador,
                                             AlnsSolution solucionDummy,
                                             Map<String, int[]> odStats) {
        LocalDateTime vf = vi.plusMinutes(SA_MINUTOS);

        List<Maleta>       maletasVentana = dataLoader.getMaletasVentana(vi);
        List<LuggageBatch> bloqueBatches  = mapper.mapToBatches(maletasVentana);

        Map<Long, Integer> blockFlight  = new HashMap<>();
        Map<Long, Integer> blockAirport = new HashMap<>();

        List<LuggageBatch> intra = new ArrayList<>();
        List<LuggageBatch> inter = new ArrayList<>();
        for (LuggageBatch b : bloqueBatches) {
            if (b.getSlaLimitHours() <= 24) intra.add(b); else inter.add(b);
        }
        intra.forEach(b -> enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport));
        inter.forEach(b -> enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport));

        List<LuggageBatch> finalBatches;
        if (bloqueBatches.stream().anyMatch(b -> !b.isCumpleSLA())) {
            AlgorithmALNS alns = new AlgorithmALNS(
                    graph, enrutador, bloqueBatches, blockFlight, blockAirport);
            alns.run(ALNS_MAX_ITERATIONS);
            finalBatches = alns.getBestSolution().getBatches();
            enrutador.commitBlock(alns.getBestBlockFlight(), alns.getBestBlockAirport());
        } else {
            finalBatches = bloqueBatches;
            enrutador.commitBlock(blockFlight, blockAirport);
        }

        List<SimulacionResponse.AsignacionMaleta> asignaciones = buildAsignaciones(finalBatches);

        int enrutadas  = (int) asignaciones.stream().filter(SimulacionResponse.AsignacionMaleta::isEnrutada).count();
        int cumpleSLA  = (int) asignaciones.stream().filter(a -> a.isEnrutada() && a.isCumpleSLA()).count();
        int tardadas   = enrutadas - cumpleSLA;
        int sinRuta    = finalBatches.size() - enrutadas;
        long maletas   = finalBatches.stream().mapToLong(LuggageBatch::getQuantity).sum();

        for (SimulacionResponse.AsignacionMaleta a : asignaciones) {
            int[] s = odStats.computeIfAbsent(a.getOrigen() + "->" + a.getDestino(), key -> new int[2]);
            s[0]++;
            if (a.isEnrutada()) s[1]++;
        }

        SimulacionResponse.BloqueSimulacion bloque = new SimulacionResponse.BloqueSimulacion();
        bloque.setHoraInicio(vi.toString());
        bloque.setHoraFin(vf.toString());
        bloque.setMaletasProcesadas(finalBatches.size());
        bloque.setMaletasEnrutadas(enrutadas);
        bloque.setAsignaciones(asignaciones);

        return new ResultadoVentana(bloque, finalBatches.size(), enrutadas, sinRuta, cumpleSLA, tardadas, maletas);
    }

    /** Construye los DTOs de asignación para una lista de batches ya ruteados. */
    private List<SimulacionResponse.AsignacionMaleta> buildAsignaciones(List<LuggageBatch> batches) {
        return batches.stream().map(b -> {
            boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            SimulacionResponse.AsignacionMaleta asig = new SimulacionResponse.AsignacionMaleta();
            asig.setBatchId(b.getId());
            asig.setOrigen(b.getOriginCode());
            asig.setDestino(b.getDestCode());
            asig.setCantidad(b.getQuantity());
            asig.setEnrutada(enrutada);
            asig.setCumpleSLA(b.isCumpleSLA());
            asig.setRutaVuelos(enrutada
                    ? b.getAssignedRoute().stream().map(e -> e.id).collect(Collectors.toList())
                    : Collections.emptyList());

            List<SimulacionResponse.TramoRuta> tramos = Collections.emptyList();
            if (enrutada && b.getAssignedDepartures() != null && !b.getAssignedDepartures().isEmpty()) {
                var route = b.getAssignedRoute();
                var deps  = b.getAssignedDepartures();
                tramos    = new ArrayList<>();
                for (int ti = 0; ti < route.size(); ti++) {
                    var  edge   = route.get(ti);
                    long depMin = deps.get(ti);
                    long arrMin = depMin + edge.durationMinutes;
                    SimulacionResponse.TramoRuta tr = new SimulacionResponse.TramoRuta();
                    tr.setVueloId(edge.id);
                    tr.setOrigen(edge.from != null ? edge.from.code : "");
                    tr.setDestino(edge.to   != null ? edge.to.code   : "");
                    tr.setSalidaUtc(epochMinToUtc(depMin));
                    tr.setLlegadaUtc(epochMinToUtc(arrMin));
                    tramos.add(tr);
                }
            }
            asig.setTramos(tramos);
            return asig;
        }).collect(Collectors.toList());
    }

    // =========================================================
    // Diagnóstico
    // =========================================================
    private void logDiagnosticos(Map<String, int[]> odStats, Graph graph, GreedyRepairOperator enrutador) {
        log.info("===== DIAGNÓSTICO =====");
        log.info("Top 25 pares O→D sin ruta:");
        odStats.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) ->
                        e.getValue()[0] - e.getValue()[1]).reversed())
                .limit(25)
                .forEach(e -> {
                    int tot = e.getValue()[0], sinR = tot - e.getValue()[1];
                    log.info("  {} | total={} sinRuta={} ({}%)", e.getKey(), tot, sinR,
                            tot > 0 ? sinR * 100 / tot : 0);
                });

        log.info("Conectividad (vuelos de salida por aeropuerto):");
        int sinSalida = 0;
        for (String code : graph.nodes.keySet()) {
            int sal = graph.getNeighbors(code).size();
            if (sal == 0) { log.warn("  AISLADO: {}", code); sinSalida++; }
            else            log.info("  {} → {} vuelos", code, sal);
        }
        if (sinSalida == 0) log.info("  Todos los aeropuertos tienen salidas.");
        enrutador.logEstadisticasCapacidad();
        log.info("=======================");
    }

    // =========================================================
    // Helpers de respuesta
    // =========================================================
    private SimulacionResponse construirRespuestaFront(int enrutadas, long tiempoMs,
                                                        List<Vuelo> vuelosReales,
                                                        int totalBloques,
                                                        LocalDate simulationDate) {
        SimulacionResponse res = new SimulacionResponse();
        SimulacionResponse.Metricas m = new SimulacionResponse.Metricas();
        m.setEnrutadas(enrutadas);
        m.setTiempoEjecucionMs(tiempoMs);
        res.setMetricas(m);
        res.setTotalBloques(totalBloques);

        long dayShift = simulationDate != null
                ? ChronoUnit.DAYS.between(FlightParser.FLIGHT_BASE_DATE, simulationDate) : 0L;

        List<SimulacionResponse.VueloBackend> vuelosFront = new ArrayList<>();
        Map<String, SimulacionResponse.AeropuertoDTO> infoAero = new HashMap<>();
        for (Vuelo v : vuelosReales) {
            SimulacionResponse.VueloBackend vb = new SimulacionResponse.VueloBackend();
            vb.setId(String.valueOf(v.getId()));
            vb.setOrigen(v.getOrigen());
            vb.setDestino(v.getDestino());
            vb.setFechaSalida(v.getFechaHoraSalida().plusDays(dayShift).toString());
            vb.setFechaLlegada(v.getFechaHoraLlegada().plusDays(dayShift).toString());
            vuelosFront.add(vb);
            agregarInfoAeropuerto(infoAero, v.getOrigen(), v.getAeropuertoOrigen());
            agregarInfoAeropuerto(infoAero, v.getDestino(), v.getAeropuertoDestino());
        }
        res.setVuelosPlaneados(vuelosFront);
        res.setAeropuertosInfo(infoAero);
        return res;
    }

    private static void llenarMetricas(SimulacionResponse.Metricas m,
                                        int envios, int enrutadas, int sinRuta,
                                        int cumpleSLA, int tardadas, long maletas,
                                        int vuelosCancelados,
                                        boolean collapso, int bloqueCollapso) {
        m.setProcesadas(envios);
        m.setEnrutadas(enrutadas);
        m.setSinRuta(sinRuta);
        m.setCumpleSLA(cumpleSLA);
        m.setTardadas(tardadas);
        m.setMaletasIndividuales(maletas);
        m.setVuelosCancelados(vuelosCancelados);
        m.setCollapsoDetectado(collapso);
        m.setBloqueColapso(bloqueCollapso);
    }

    private static String epochMinToUtc(long epochMin) {
        long epochDay    = epochMin / 1440L;
        int  minuteOfDay = (int)(epochMin % 1440L);
        return LocalDateTime.of(
                LocalDate.ofEpochDay(epochDay),
                LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        ).toString();
    }

    private void agregarInfoAeropuerto(Map<String, SimulacionResponse.AeropuertoDTO> map,
                                        String cod, Aeropuerto a) {
        if (!map.containsKey(cod)) {
            SimulacionResponse.AeropuertoDTO dto = new SimulacionResponse.AeropuertoDTO();
            dto.setCodigo(cod);
            dto.setLatitud(a.getLatitud());
            dto.setLongitud(a.getLongitud());
            map.put(cod, dto);
        }
    }

    // =========================================================
    // Clases internas de apoyo
    // =========================================================
    private record ResultadoVentana(
            SimulacionResponse.BloqueSimulacion bloque,
            int envios, int enrutadas, int sinRuta, int cumpleSLA, int tardadas, long maletas) {}
}
