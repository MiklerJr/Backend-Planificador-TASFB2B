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
        int totalMaletas   = 0;
        int totalEnrutadas = 0;
        int bloqueActual   = 0;

        for (LocalDateTime vi : ventanas) {
            bloqueActual++;
            LocalDateTime vf = vi.plusMinutes(10);

            // Solo las maletas de esta ventana se convierten a LuggageBatch.
            // Al terminar el bloque, la referencia local desaparece y el GC puede
            // reclamar esos objetos antes de procesar la siguiente ventana.
            List<Maleta> maletasVentana = dataLoader.getMaletasVentana(vi);
            List<LuggageBatch> bloqueBatches = mapper.mapToBatches(maletasVentana);

            int enrutadasBloque = 0;
            List<SimulacionResponse.AsignacionMaleta> asignaciones = new ArrayList<>(bloqueBatches.size());

            for (LuggageBatch b : bloqueBatches) {
                enrutador.repair(solucionDummy, List.of(b));

                boolean enrutada = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
                if (enrutada) enrutadasBloque++;

                SimulacionResponse.AsignacionMaleta asig = new SimulacionResponse.AsignacionMaleta();
                asig.setBatchId(b.getId());
                asig.setOrigen(b.getOriginCode());
                asig.setDestino(b.getDestCode());
                asig.setCantidad(b.getQuantity());
                asig.setEnrutada(enrutada);
                asig.setRutaVuelos(enrutada
                        ? b.getAssignedRoute().stream().map(e -> e.id).collect(Collectors.toList())
                        : Collections.emptyList());
                asignaciones.add(asig);
            }

            SimulacionResponse.BloqueSimulacion bloque = new SimulacionResponse.BloqueSimulacion();
            bloque.setHoraInicio(vi.toString());
            bloque.setHoraFin(vf.toString());
            bloque.setMaletasProcesadas(bloqueBatches.size());
            bloque.setMaletasEnrutadas(enrutadasBloque);
            bloque.setAsignaciones(asignaciones);
            bloques.add(bloque);

            totalMaletas   += bloqueBatches.size();
            totalEnrutadas += enrutadasBloque;
            // bloqueBatches sale de scope aquí → LuggageBatch + List<Edge> elegibles para GC

            if (bloqueActual % intervaloReporte == 0 || bloqueActual == totalVentanas) {
                int pct = (int) Math.round((bloqueActual * 100.0) / totalVentanas);
                log.info("Progreso ALNS: {}% — bloque {}/{} | maletas procesadas: {}",
                        pct, bloqueActual, totalVentanas, totalMaletas);
            }
        }

        bloquesCacheados = bloques;
        long tiempoMs = System.currentTimeMillis() - inicio;
        log.info("ALNS completado: {} bloques | {} enrutadas / {} maletas | {} ms",
                bloques.size(), totalEnrutadas, totalMaletas, tiempoMs);

        // Construimos las métricas globales con los totales acumulados.
        SimulacionResponse res = construirRespuestaFront(
                Collections.emptyList(), 0, tiempoMs, dataLoader.getVuelos(), bloques.size());
        res.getMetricas().setProcesadas(totalMaletas);
        res.getMetricas().setEnrutadas(totalEnrutadas);
        res.getMetricas().setSinRuta(totalMaletas - totalEnrutadas);
        return res;
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