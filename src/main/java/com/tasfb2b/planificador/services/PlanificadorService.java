package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.alns.AlgorithmALNS;
import com.tasfb2b.planificador.algorithm.alns.AlnsSolution;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.Vuelo;
import com.tasfb2b.planificador.util.AlgorithmMapper;
import com.tasfb2b.planificador.util.DataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PlanificadorService {

    private final DataLoader dataLoader;
    private final AlgorithmMapper mapper;

    public PlanificadorService(DataLoader dataLoader, AlgorithmMapper mapper) {
        this.dataLoader = dataLoader;
        this.mapper = mapper;
    }

    public SimulacionResponse ejecutarAlgoritmo() {
        log.info("Iniciando mapeo de grafos...");
        Graph graph = mapper.mapToGraph(dataLoader.getAeropuertos(), dataLoader.getVuelos());

        // --- OPTIMIZACIÓN DE MEMORIA EXTREMA ---
        log.info("Obteniendo maletas de la base de datos virtual...");
        List<Maleta> todasLasMaletas = dataLoader.getMaletas();
        log.info("Se cargaron {} maletas en memoria cruda.", todasLasMaletas.size());

        // 1. Tomamos solo una muestra manejable (ej. 100 para pruebas)
        int limitePrueba = Math.min(100, todasLasMaletas.size());
        List<Maleta> maletasPrueba = todasLasMaletas.subList(0, limitePrueba);

        // 2. Mapeamos SOLO la muestra
        List<LuggageBatch> testBatches = mapper.mapToBatches(maletasPrueba);

        // 3. Destruimos la referencia a la lista gigante y forzamos la limpieza de RAM
        todasLasMaletas = null;
        System.gc(); // Llamamos al Garbage Collector
        log.info("Memoria liberada. Trabajando solo con {} lotes.", testBatches.size());

        // 4. Ordenar cronológicamente la muestra
        testBatches.sort(Comparator.comparing(LuggageBatch::getReadyTime));
        log.info("Datos ordenados. La primera maleta llega el: {}", testBatches.get(0).getReadyTime());

        // --- SIMULAR EL TIEMPO REAL ---
        AlnsSolution estadoDeLaRed = new AlnsSolution(new ArrayList<>());
        GreedyRepairOperator enrutador = new GreedyRepairOperator(graph);

        int maletasEnrutadas = 0;
        int sinRuta = 0;

        log.info("Iniciando enrutamiento matemático...");
        long startTime = System.currentTimeMillis();

        for (LuggageBatch batchActual : testBatches) {
            // Buscamos ruta
            enrutador.repair(estadoDeLaRed, List.of(batchActual));

            // Agregamos la maleta a la red
            estadoDeLaRed.getBatches().add(batchActual);

            if (batchActual.getAssignedRoute() != null && !batchActual.getAssignedRoute().isEmpty()) {
                maletasEnrutadas++;
            } else {
                sinRuta++;
            }
        }

        long endTime = System.currentTimeMillis();
        log.info("Enrutamiento finalizado en {} ms.", (endTime - startTime));

        // ---------------------------------------------------------
        // ARMADO DEL JSON PARA EL FRONTEND (SimulacionResponse)
        // ---------------------------------------------------------
        SimulacionResponse respuesta = new SimulacionResponse();

        // 1. Asignamos las métricas reales
        SimulacionResponse.Metricas metricas = new SimulacionResponse.Metricas();
        metricas.setProcesadas(testBatches.size());
        metricas.setEnrutadas(maletasEnrutadas);
        metricas.setSinRuta(sinRuta);
        respuesta.setMetricas(metricas);

        // 2. Asignamos los vuelos reales y extraemos los aeropuertos
        List<SimulacionResponse.VueloBackend> vuelosFront = new ArrayList<>();
        Map<String, SimulacionResponse.AeropuertoDTO> infoAeropuertos = new HashMap<>();

        // Iteramos los vuelos del DataLoader
        List<Vuelo> vuelosReales = dataLoader.getVuelos();

        for(Vuelo v : vuelosReales) {
            SimulacionResponse.VueloBackend vBackend = new SimulacionResponse.VueloBackend();

            // 1. Convertimos el Integer del ID a String
            vBackend.setId(String.valueOf(v.getId()));

            // 2. Origen y destino
            vBackend.setOrigen(v.getOrigen());
            vBackend.setDestino(v.getDestino());

            // 3. Nombres correctos para las fechas
            vBackend.setFechaSalida(v.getFechaHoraSalida().toString());
            vBackend.setFechaLlegada(v.getFechaHoraLlegada().toString());

            // 4. Nombre correcto de la capacidad
            vBackend.setCapacidadMaxima(v.getCapacidad());
            vBackend.setCargaAsignada(0);

            vuelosFront.add(vBackend);

            // --- NUEVO: EXTRAER COORDENADAS DE LOS AEROPUERTOS ---

            // Origen
            if (!infoAeropuertos.containsKey(v.getOrigen())) {
                SimulacionResponse.AeropuertoDTO origenDto = new SimulacionResponse.AeropuertoDTO();
                origenDto.setCodigo(v.getOrigen());
                origenDto.setLatitud(v.getAeropuertoOrigen().getLatitud());
                origenDto.setLongitud(v.getAeropuertoOrigen().getLongitud());
                infoAeropuertos.put(v.getOrigen(), origenDto);
            }

            // Destino
            if (!infoAeropuertos.containsKey(v.getDestino())) {
                SimulacionResponse.AeropuertoDTO destinoDto = new SimulacionResponse.AeropuertoDTO();
                destinoDto.setCodigo(v.getDestino());
                destinoDto.setLatitud(v.getAeropuertoDestino().getLatitud());
                destinoDto.setLongitud(v.getAeropuertoDestino().getLongitud());
                infoAeropuertos.put(v.getDestino(), destinoDto);
            }
        }

        respuesta.setVuelosPlaneados(vuelosFront);
        respuesta.setAeropuertosInfo(infoAeropuertos); // Agregamos el mapa al JSON

        return respuesta;
    }
}