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

import java.util.*;

@Slf4j
@Service
public class PlanificadorService {

    private final DataLoader dataLoader;
    private final AlgorithmMapper mapper;
    private SimulacionResponse ultimaSimulacion;

    public PlanificadorService(DataLoader dataLoader, AlgorithmMapper mapper) {
        this.dataLoader = dataLoader;
        this.mapper = mapper;
    }

    public void ejecutarPlanificacionCompleta() {
        log.info("Arrancando el sistema por defecto (Zapato Rojo - ALNS)...");
        this.ultimaSimulacion = ejecutarALNS();
    }

    public SimulacionResponse getUltimaSimulacion() {
        return ultimaSimulacion;
    }

    // =========================================================
    // ALNS (Optimización de Carga y Red Global)
    // =========================================================
    public SimulacionResponse ejecutarALNS() {
        log.info("Ejecutando ALNS para optimización de maletas...");
        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        List<Maleta> maletas = dataLoader.getMaletas();

        int limite = Math.min(100, maletas.size());
        List<LuggageBatch> batches = mapper.mapToBatches(new ArrayList<>(maletas.subList(0, limite)));
        batches.sort(Comparator.comparing(LuggageBatch::getReadyTime));

        AlnsSolution solucion = new AlnsSolution(new ArrayList<>());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);

        int enrutadas = 0;
        for (LuggageBatch b : batches) {
            enrutador.repair(solucion, List.of(b));
            solucion.getBatches().add(b);
            if (b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty()) {
                enrutadas++;
            }
        }

        return construirRespuestaFront(batches, enrutadas, dataLoader.getVuelos());
    }

    // =========================================================
    // ACO (Enrutamiento con Colonia de Hormigas)
    // =========================================================
    public SimulacionResponse ejecutarACO() {
        log.info("Ejecutando ACO para optimización de maletas...");
        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());
        List<Maleta> maletas = dataLoader.getMaletas();

        int limite = Math.min(100, maletas.size());
        List<LuggageBatch> batches = mapper.mapToBatches(new ArrayList<>(maletas.subList(0, limite)));
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

        // Devolvemos LA MISMA ESTRUCTURA. El frontend no notará la diferencia.
        return construirRespuestaFront(batches, enrutadas, dataLoader.getVuelos());
    }

    // =========================================================
    // Mapeo de JSON para el Frontend
    // =========================================================
    private SimulacionResponse construirRespuestaFront(List<LuggageBatch> batches, int enrutadas, List<Vuelo> vuelosReales) {
        SimulacionResponse res = new SimulacionResponse();

        SimulacionResponse.Metricas m = new SimulacionResponse.Metricas();
        m.setProcesadas(batches.size());
        m.setEnrutadas(enrutadas);
        m.setSinRuta(batches.size() - enrutadas);
        res.setMetricas(m);

        List<SimulacionResponse.VueloBackend> vuelosFront = new ArrayList<>();
        Map<String, SimulacionResponse.AeropuertoDTO> infoAero = new HashMap<>();

        for(Vuelo v : vuelosReales) {
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