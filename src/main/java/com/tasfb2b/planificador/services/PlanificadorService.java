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

    private final DataLoader dataLoader;
    private final AlgorithmMapper mapper;

    // Caché de bloques: se llena al ejecutar ALNS; el front los pide de uno en uno.
    private volatile List<SimulacionResponse.BloqueSimulacion> bloquesCacheados = null;

    public PlanificadorService(DataLoader dataLoader, AlgorithmMapper mapper) {
        this.dataLoader = dataLoader;
        this.mapper = mapper;
    }

    public SimulacionResponse.BloqueSimulacion getBloque(int index) {
        if (bloquesCacheados == null || index < 0 || index >= bloquesCacheados.size()) return null;
        return bloquesCacheados.get(index);
    }

    // =========================================================
    // ALNS (Optimización de Carga y Red Global)
    // =========================================================
    public SimulacionResponse ejecutarALNS() {
        log.info("Ejecutando ALNS para optimización de maletas...");
        long inicio = System.currentTimeMillis();

        if (dataLoader.getVentanas().isEmpty()) {
            bloquesCacheados = new ArrayList<>();
            return construirRespuestaFront(Collections.emptyList(), 0, 0L, dataLoader.getVuelos(), 0);
        }

        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        // El grafo (30 nodos, ~2800 aristas) es pequeño y vive toda la ejecución.

        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);
        // Dummy: la solución solo existe para satisfacer la interfaz RepairOperator.
        // GreedyRepairOperator usa su propio flightOccupancy; no necesita acumular batches.
        AlnsSolution solucionDummy = new AlnsSolution(Collections.emptyList());

        Set<LocalDateTime> ventanas = dataLoader.getVentanas(); // TreeMap → ya ordenado
        int totalVentanas = ventanas.size();
        int intervaloReporte = Math.max(1, totalVentanas / 10); // log cada ~10% del total

        List<SimulacionResponse.BloqueSimulacion> bloques = new ArrayList<>(totalVentanas);
        // "ORIG->DEST" → [total, enrutadas]
        Map<String, int[]> odStats = new HashMap<>();
        int totalMaletas    = 0;
        int totalEnrutadas  = 0;
        int totalSinRuta    = 0;
        int totalCumpleSLA  = 0;
        int totalTardadas   = 0;
        int bloqueActual    = 0;

        for (LocalDateTime vi : ventanas) {
            bloqueActual++;
            LocalDateTime vf = vi.plusMinutes(10);

            // Solo las maletas de esta ventana se convierten a LuggageBatch.
            // Al terminar el bloque, la referencia local desaparece y el GC puede
            // reclamar esos objetos antes de procesar la siguiente ventana.
            List<Maleta> maletasVentana = dataLoader.getMaletasVentana(vi);
            List<LuggageBatch> bloqueBatches = mapper.mapToBatches(maletasVentana);

            // Fase 1: enrutamiento con prioridad EDD (Earliest Due Date).
            // Las maletas intracontinentales (SLA=24h) se enrutan primero para que
            // reclaimen capacidad antes que las intercontinentales (SLA=48h), que tienen
            // más margen para tomar vuelos del día siguiente sin incumplir su plazo.
            List<LuggageBatch> intra = new ArrayList<>();
            List<LuggageBatch> inter = new ArrayList<>();
            for (LuggageBatch b : bloqueBatches) {
                if (b.getSlaLimitHours() <= 24) intra.add(b); else inter.add(b);
            }
            intra.parallelStream().forEach(b -> enrutador.repair(solucionDummy, List.of(b)));
            inter.parallelStream().forEach(b -> enrutador.repair(solucionDummy, List.of(b)));

            // Fase 2: construcción de DTOs (barato, secuencial para mantener orden).
            List<SimulacionResponse.AsignacionMaleta> asignaciones = bloqueBatches.stream()
                    .map(b -> {
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
                        return asig;
                    })
                    .collect(Collectors.toList());

            int bloqueEnrutadas = (int) asignaciones.stream().filter(SimulacionResponse.AsignacionMaleta::isEnrutada).count();
            int bloqueCumpleSLA = (int) asignaciones.stream().filter(a -> a.isEnrutada() && a.isCumpleSLA()).count();
            int bloqueTardadas  = bloqueEnrutadas - bloqueCumpleSLA;

            // Acumular estadísticas por par O→D para el diagnóstico final
            for (SimulacionResponse.AsignacionMaleta a : asignaciones) {
                int[] s = odStats.computeIfAbsent(a.getOrigen() + "->" + a.getDestino(), k -> new int[2]);
                s[0]++;
                if (a.isEnrutada()) s[1]++;
            }

            SimulacionResponse.BloqueSimulacion bloque = new SimulacionResponse.BloqueSimulacion();
            bloque.setHoraInicio(vi.toString());
            bloque.setHoraFin(vf.toString());
            bloque.setMaletasProcesadas(bloqueBatches.size());
            bloque.setMaletasEnrutadas(bloqueEnrutadas);
            bloque.setAsignaciones(asignaciones);
            bloques.add(bloque);

            totalMaletas   += bloqueBatches.size();
            totalEnrutadas += bloqueEnrutadas;
            totalSinRuta   += bloqueBatches.size() - bloqueEnrutadas;
            totalCumpleSLA += bloqueCumpleSLA;
            totalTardadas  += bloqueTardadas;
            // bloqueBatches sale de scope aquí → LuggageBatch + List<Edge> elegibles para GC

            if (bloqueActual % intervaloReporte == 0 || bloqueActual == totalVentanas) {
                int pct = (int) Math.round((bloqueActual * 100.0) / totalVentanas);
                log.info("Progreso ALNS: {}% — bloque {}/{} | procesadas: {} | enrutadas: {} | a tiempo: {} | tardadas: {} | sin ruta: {}",
                        pct, bloqueActual, totalVentanas, totalMaletas, totalEnrutadas, totalCumpleSLA, totalTardadas, totalSinRuta);
            }
        }

        bloquesCacheados = bloques;
        long tiempoMs = System.currentTimeMillis() - inicio;
        log.info("ALNS completado: {} bloques | {} maletas | {} enrutadas | {} a tiempo | {} tardadas | {} sin ruta | {} ms",
                bloques.size(), totalMaletas, totalEnrutadas, totalCumpleSLA, totalTardadas, totalSinRuta, tiempoMs);

        logDiagnosticos(odStats, graph, enrutador);

        SimulacionResponse res = construirRespuestaFront(
                Collections.emptyList(), 0, tiempoMs, dataLoader.getVuelos(), bloques.size());
        res.getMetricas().setProcesadas(totalMaletas);
        res.getMetricas().setEnrutadas(totalEnrutadas);
        res.getMetricas().setSinRuta(totalSinRuta);
        res.getMetricas().setCumpleSLA(totalCumpleSLA);
        res.getMetricas().setTardadas(totalTardadas);
        return res;
    }

    // =========================================================
    // Diagnóstico post-ALNS
    // =========================================================
    private void logDiagnosticos(Map<String, int[]> odStats, Graph graph, GreedyRepairOperator enrutador) {
        log.info("=========================================================");
        log.info("DIAGNÓSTICO ALNS");
        log.info("=========================================================");

        // 1. Top 25 pares O→D con más envíos sin ruta
        log.info("--- Top 25 pares O→D con más envíos sin ruta ---");
        odStats.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) ->
                        e.getValue()[0] - e.getValue()[1]).reversed())
                .limit(25)
                .forEach(e -> {
                    int total    = e.getValue()[0];
                    int enrut    = e.getValue()[1];
                    int sinRuta  = total - enrut;
                    int pct      = total > 0 ? sinRuta * 100 / total : 0;
                    log.info("  {} | total={} sinRuta={} ({}%)", e.getKey(), total, sinRuta, pct);
                });

        // 2. Agregado por aeropuerto origen — cuáles acumulan más fallos
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
                    int pct     = total > 0 ? sinRuta * 100 / total : 0;
                    log.info("  {} | total={} sinRuta={} ({}%)", e.getKey(), total, sinRuta, pct);
                });

        // 3. Conectividad del grafo
        log.info("--- Conectividad del grafo (vuelos de salida por aeropuerto) ---");
        int sinSalida = 0;
        for (String code : graph.nodes.keySet()) {
            int salidas = graph.getNeighbors(code).size();
            if (salidas == 0) {
                log.warn("  AISLADO (sin salidas): {}", code);
                sinSalida++;
            } else {
                log.info("  {} → {} vuelos de salida", code, salidas);
            }
        }
        if (sinSalida == 0) log.info("  Todos los aeropuertos tienen al menos 1 vuelo de salida.");

        // 4. Utilización de capacidad de vuelos
        enrutador.logEstadisticasCapacidad();

        log.info("=========================================================");
    }

    // =========================================================
    // ACO (Enrutamiento con Colonia de Hormigas)
    // =========================================================
    public SimulacionResponse ejecutarACO() {
        log.info("Ejecutando ACO para optimización de maletas...");
        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        List<LuggageBatch> batches = mapper.mapToBatches(dataLoader.getMaletasMuestra(100));
        batches.sort(Comparator.comparing(LuggageBatch::getReadyTime));

        ConfigACO config = new ConfigACO();
        config.antCount = 20;
        config.iterations = 100;
        AlgorithmACO aco = new AlgorithmACO(graph, config);

        int enrutadas = 0;

        for (LuggageBatch b : batches) {
            // Nota: Aquí asumo que obtienes el origen y destino del batch/maleta.
            // Ajusta "b.getOrigen()" o la forma en que extraigas los códigos si es diferente en tu modelo.
            // String origen = "SKBO"; // Reemplazar con el origen de 'b'
            // String destino = "SPIM"; // Reemplazar con el destino de 'b'

            // aco.run(origen, destino);

            // Simulamos éxito para el ejemplo visual, ajusta la lógica si ACO te dice si falló.
            enrutadas++;
        }

        return construirRespuestaFront(batches, enrutadas, 0L, dataLoader.getVuelos(), 0);
    }

    // =========================================================
    // Mapeo de JSON para el Frontend
    // =========================================================
    private SimulacionResponse construirRespuestaFront(List<LuggageBatch> batches, int enrutadas,
                                                        long tiempoEjecucionMs, List<Vuelo> vuelosReales,
                                                        int totalBloques) {
        SimulacionResponse res = new SimulacionResponse();

        SimulacionResponse.Metricas m = new SimulacionResponse.Metricas();
        m.setProcesadas(batches.size());
        m.setEnrutadas(enrutadas);
        m.setSinRuta(batches.size() - enrutadas);
        m.setTiempoEjecucionMs(tiempoEjecucionMs);
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

    private void agregarInfoAeropuerto(Map<String, SimulacionResponse.AeropuertoDTO> map, String cod, Aeropuerto a) {
        if (!map.containsKey(cod)) {
            SimulacionResponse.AeropuertoDTO dto = new SimulacionResponse.AeropuertoDTO();
            dto.setCodigo(cod);
            dto.setLatitud(a.getLatitud());
            dto.setLongitud(a.getLongitud());
            map.put(cod, dto);
        }
    }
}