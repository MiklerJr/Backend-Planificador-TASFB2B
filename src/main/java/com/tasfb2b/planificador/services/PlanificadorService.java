package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.algorithm.alns.*;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.Vuelo;
import com.tasfb2b.planificador.util.AlgorithmMapper;
import com.tasfb2b.planificador.util.DataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlanificadorService {

    // Iteraciones ALNS por bloque (debe ser múltiplo de AlgorithmALNS.segmentLength=3
    // para que el mecanismo adaptativo actualice pesos en todos los segmentos).
    // 9 iters → 3 segmentos → overhead ≈ 1.8× greedy (≈20 min en i9-14900HX).
    private static final int ALNS_MAX_ITERATIONS = 9;

    private final DataLoader dataLoader;
    private final AlgorithmMapper mapper;

    private volatile List<SimulacionResponse.BloqueSimulacion> bloquesCacheados = null;

    public PlanificadorService(DataLoader dataLoader, AlgorithmMapper mapper) {
        this.dataLoader = dataLoader;
        this.mapper = mapper;
    }

    public SimulacionResponse.BloqueSimulacion getBloque(int index) {
        if (bloquesCacheados == null || index < 0 || index >= bloquesCacheados.size()) return null;
        return bloquesCacheados.get(index);
    }

    // Tamaño de ventana fijo en minutos (Sa)
    private static final int SA_MINUTOS = 10;

    // =========================================================
    // ALNS (Adaptive Large Neighbourhood Search)
    // =========================================================
    public SimulacionResponse ejecutarALNS(int k) {
        log.info("Ejecutando ALNS ({} iters/bloque, K={}, Sa={}min, Sc={}min) ...",
                ALNS_MAX_ITERATIONS, k, SA_MINUTOS, k * SA_MINUTOS);
        long inicio = System.currentTimeMillis();

        if (dataLoader.getVentanas().isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            SimulacionResponse r = construirRespuestaFront(Collections.emptyList(), 0, 0L,
                    dataLoader.getVuelos(), 0);
            r.setK(k);
            r.setSaMinutos(SA_MINUTOS);
            return r;
        }

        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        AlnsSolution solucionDummy     = new AlnsSolution(Collections.emptyList());

        Set<LocalDateTime> ventanas       = dataLoader.getVentanas();
        int totalVentanas                 = ventanas.size();
        int intervaloReporte              = Math.max(1, totalVentanas / 10);

        List<SimulacionResponse.BloqueSimulacion> bloques = new ArrayList<>(totalVentanas);
        Map<String, int[]> odStats = new HashMap<>();
        int totalMaletas = 0, totalEnrutadas = 0, totalSinRuta = 0,
            totalCumpleSLA = 0, totalTardadas = 0, bloqueActual = 0;

        for (LocalDateTime vi : ventanas) {
            bloqueActual++;
            LocalDateTime vf = vi.plusMinutes(10);

            List<Maleta>       maletasVentana = dataLoader.getMaletasVentana(vi);
            List<LuggageBatch> bloqueBatches  = mapper.mapToBatches(maletasVentana);

            // ── Fase 1: Solución inicial greedy con prioridad EDD ────────────
            Map<Long, Integer> blockFlight  = new HashMap<>();
            Map<Long, Integer> blockAirport = new HashMap<>();

            List<LuggageBatch> intra = new ArrayList<>();
            List<LuggageBatch> inter = new ArrayList<>();
            for (LuggageBatch b : bloqueBatches) {
                if (b.getSlaLimitHours() <= 24) intra.add(b); else inter.add(b);
            }
            // Intra primero (plazo 24 h): reclaman capacidad antes que las inter (48 h).
            // Secuencial para respetar las restricciones de capacidad sin race conditions.
            intra.forEach(b -> enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport));
            inter.forEach(b -> enrutador.repair(solucionDummy, List.of(b), blockFlight, blockAirport));

            // ── Fase 2: ALNS — solo si el greedy dejó alguna tardada ─────────
            // Con ~20 tardadas en 9.5M bags, casi todos los bloques saltan esta fase.
            List<LuggageBatch> finalBatches;
            boolean hayTardadas = bloqueBatches.stream().anyMatch(b -> !b.isCumpleSLA());

            if (hayTardadas) {
                AlgorithmALNS alns = new AlgorithmALNS(
                        graph, enrutador, bloqueBatches, blockFlight, blockAirport);
                alns.run(ALNS_MAX_ITERATIONS);
                finalBatches = alns.getBestSolution().getBatches();
                enrutador.commitBlock(alns.getBestBlockFlight(), alns.getBestBlockAirport());
            } else {
                // Greedy ya óptimo: confirmar sin iteraciones ALNS
                finalBatches = bloqueBatches;
                enrutador.commitBlock(blockFlight, blockAirport);
            }

            // ── Fase 4: Construir DTOs para el frontend ───────────────────────
            List<SimulacionResponse.AsignacionMaleta> asignaciones = finalBatches.stream()
                    .map(b -> {
                        boolean enrutada = b.getAssignedRoute() != null
                                        && !b.getAssignedRoute().isEmpty();
                        SimulacionResponse.AsignacionMaleta asig =
                                new SimulacionResponse.AsignacionMaleta();
                        asig.setBatchId(b.getId());
                        asig.setOrigen(b.getOriginCode());
                        asig.setDestino(b.getDestCode());
                        asig.setCantidad(b.getQuantity());
                        asig.setEnrutada(enrutada);
                        asig.setCumpleSLA(b.isCumpleSLA());
                        asig.setRutaVuelos(enrutada
                                ? b.getAssignedRoute().stream()
                                        .map(e -> e.id).collect(Collectors.toList())
                                : Collections.emptyList());
                        return asig;
                    })
                    .collect(Collectors.toList());

            int bloqueEnrutadas = (int) asignaciones.stream()
                    .filter(SimulacionResponse.AsignacionMaleta::isEnrutada).count();
            int bloqueCumpleSLA = (int) asignaciones.stream()
                    .filter(a -> a.isEnrutada() && a.isCumpleSLA()).count();
            int bloqueTardadas  = bloqueEnrutadas - bloqueCumpleSLA;

            for (SimulacionResponse.AsignacionMaleta a : asignaciones) {
                int[] s = odStats.computeIfAbsent(
                        a.getOrigen() + "->" + a.getDestino(), key -> new int[2]);
                s[0]++;
                if (a.isEnrutada()) s[1]++;
            }

            SimulacionResponse.BloqueSimulacion bloque = new SimulacionResponse.BloqueSimulacion();
            bloque.setHoraInicio(vi.toString());
            bloque.setHoraFin(vf.toString());
            bloque.setMaletasProcesadas(finalBatches.size());
            bloque.setMaletasEnrutadas(bloqueEnrutadas);
            bloque.setAsignaciones(asignaciones);
            bloques.add(bloque);

            totalMaletas   += finalBatches.size();
            totalEnrutadas += bloqueEnrutadas;
            totalSinRuta   += finalBatches.size() - bloqueEnrutadas;
            totalCumpleSLA += bloqueCumpleSLA;
            totalTardadas  += bloqueTardadas;

            if (bloqueActual % intervaloReporte == 0 || bloqueActual == totalVentanas) {
                int pct = (int) Math.round((bloqueActual * 100.0) / totalVentanas);
                log.info("Progreso ALNS: {}% — bloque {}/{} | procesadas: {} | enrutadas: {} "
                       + "| a tiempo: {} | tardadas: {} | sin ruta: {}",
                        pct, bloqueActual, totalVentanas,
                        totalMaletas, totalEnrutadas, totalCumpleSLA, totalTardadas, totalSinRuta);
            }
        }

        bloquesCacheados = bloques;
        long tiempoMs = System.currentTimeMillis() - inicio;
        log.info("ALNS completado: {} bloques | {} maletas | {} enrutadas | {} a tiempo "
               + "| {} tardadas | {} sin ruta | {} ms",
                bloques.size(), totalMaletas, totalEnrutadas,
                totalCumpleSLA, totalTardadas, totalSinRuta, tiempoMs);

        logDiagnosticos(odStats, graph, enrutador);

        SimulacionResponse res = construirRespuestaFront(
                Collections.emptyList(), 0, tiempoMs, dataLoader.getVuelos(), bloques.size());
        res.getMetricas().setProcesadas(totalMaletas);
        res.getMetricas().setEnrutadas(totalEnrutadas);
        res.getMetricas().setSinRuta(totalSinRuta);
        res.getMetricas().setCumpleSLA(totalCumpleSLA);
        res.getMetricas().setTardadas(totalTardadas);
        res.setK(k);
        res.setSaMinutos(SA_MINUTOS);
        return res;
    }

    // =========================================================
    // Diagnóstico post-ALNS
    // =========================================================
    private void logDiagnosticos(Map<String, int[]> odStats,
                                  Graph graph,
                                  GreedyRepairOperator enrutador) {
        log.info("=========================================================");
        log.info("DIAGNÓSTICO ALNS");
        log.info("=========================================================");

        log.info("--- Top 25 pares O→D con más envíos sin ruta ---");
        odStats.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) ->
                        e.getValue()[0] - e.getValue()[1]).reversed())
                .limit(25)
                .forEach(e -> {
                    int total   = e.getValue()[0];
                    int sinRuta = total - e.getValue()[1];
                    log.info("  {} | total={} sinRuta={} ({}%)",
                            e.getKey(), total, sinRuta,
                            total > 0 ? sinRuta * 100 / total : 0);
                });

        log.info("--- Aeropuertos con más envíos sin ruta (por origen) ---");
        Map<String, int[]> porOrigen = new HashMap<>();
        for (Map.Entry<String, int[]> e : odStats.entrySet()) {
            String orig = e.getKey().split("->")[0];
            int[] s = porOrigen.computeIfAbsent(orig, k -> new int[2]);
            s[0] += e.getValue()[0];
            s[1] += e.getValue()[1];
        }
        porOrigen.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) ->
                        e.getValue()[0] - e.getValue()[1]).reversed())
                .forEach(e -> {
                    int total   = e.getValue()[0];
                    int sinRuta = total - e.getValue()[1];
                    log.info("  {} | total={} sinRuta={} ({}%)",
                            e.getKey(), total, sinRuta,
                            total > 0 ? sinRuta * 100 / total : 0);
                });

        log.info("--- Conectividad del grafo (vuelos de salida por aeropuerto) ---");
        int sinSalida = 0;
        for (String code : graph.nodes.keySet()) {
            int salidas = graph.getNeighbors(code).size();
            if (salidas == 0) { log.warn("  AISLADO (sin salidas): {}", code); sinSalida++; }
            else               log.info("  {} → {} vuelos de salida", code, salidas);
        }
        if (sinSalida == 0) log.info("  Todos los aeropuertos tienen al menos 1 vuelo de salida.");

        enrutador.logEstadisticasCapacidad();
        log.info("=========================================================");
    }

    // =========================================================
    // ACO
    // =========================================================
    public SimulacionResponse ejecutarACO() {
        log.info("Ejecutando ACO para optimización de maletas...");
        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        List<LuggageBatch> batches = mapper.mapToBatches(dataLoader.getMaletasMuestra(100));
        batches.sort(Comparator.comparing(LuggageBatch::getReadyTime));

        ConfigACO config = new ConfigACO();
        config.antCount = 20;
        config.iterations = 100;
        new AlgorithmACO(graph, config);

        return construirRespuestaFront(batches, batches.size(), 0L,
                dataLoader.getVuelos(), 0);
    }

    // =========================================================
    // Mapeo para el Frontend
    // =========================================================
    private SimulacionResponse construirRespuestaFront(List<LuggageBatch> batches,
                                                        int enrutadas,
                                                        long tiempoMs,
                                                        List<Vuelo> vuelosReales,
                                                        int totalBloques) {
        SimulacionResponse res = new SimulacionResponse();

        SimulacionResponse.Metricas m = new SimulacionResponse.Metricas();
        m.setProcesadas(batches.size());
        m.setEnrutadas(enrutadas);
        m.setSinRuta(batches.size() - enrutadas);
        m.setTiempoEjecucionMs(tiempoMs);
        res.setMetricas(m);
        res.setTotalBloques(totalBloques);

        List<SimulacionResponse.VueloBackend> vuelosFront = new ArrayList<>();
        Map<String, SimulacionResponse.AeropuertoDTO> infoAero = new HashMap<>();
        for (Vuelo v : vuelosReales) {
            SimulacionResponse.VueloBackend vb = new SimulacionResponse.VueloBackend();
            vb.setId(String.valueOf(v.getId()));
            vb.setOrigen(v.getOrigen());
            vb.setDestino(v.getDestino());
            vb.setFechaSalida(v.getFechaHoraSalida().toString());
            vb.setFechaLlegada(v.getFechaHoraLlegada().toString());
            vuelosFront.add(vb);
            agregarInfoAeropuerto(infoAero, v.getOrigen(), v.getAeropuertoOrigen());
            agregarInfoAeropuerto(infoAero, v.getDestino(), v.getAeropuertoDestino());
        }
        res.setVuelosPlaneados(vuelosFront);
        res.setAeropuertosInfo(infoAero);
        return res;
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
}
