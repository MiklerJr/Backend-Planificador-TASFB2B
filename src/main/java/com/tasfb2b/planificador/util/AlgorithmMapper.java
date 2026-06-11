package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import java.time.Duration;
import java.time.LocalDateTime;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.Vuelo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AlgorithmMapper {

    /**
     * Convierte los modelos JPA a la estructura de Grafo del algoritmo.
     */
    public Graph mapToGraph(List<Aeropuerto> aeropuertos, List<Vuelo> vuelos) {
        Graph graph = new Graph();

        // 1. Mapear Nodos (Aeropuertos)
        for (Aeropuerto a : aeropuertos) {
            graph.addNode(a.getCodigo());
            Node nodo = graph.nodes.get(a.getCodigo());
            int capacidadAlmacen = a.getCapacidad() != null ? a.getCapacidad() : 0;
            nodo.capacity = capacidadAlmacen;
            // El ACO consulta storageCapacity (vs Node.storageUsed) en buildSolution.
            // El ALNS consulta capacity. Mantener ambos sincronizados para que ambos motores funcionen.
            nodo.storageCapacity = capacidadAlmacen;
        }

        // 2. Mapear Aristas (Vuelos)
        int edgeIdx = 0;
        for (Vuelo v : vuelos) {
            Edge edge = new Edge();

            // --- CORRECCIÓN AQUÍ ---
            // Si el Vuelo no tiene ID (porque viene del TXT), le creamos uno descriptivo
            if (v.getId() != null) {
                edge.id = v.getId().toString();
            } else {
                edge.id = v.getAeropuertoOrigen().getCodigo() + "-" +
                        v.getAeropuertoDestino().getCodigo() + "-" +
                        v.getFechaHoraSalida().toLocalTime().toString();
            }

            // Asignamos los objetos Node directamente desde el diccionario del Graph
            edge.from = graph.nodes.get(v.getAeropuertoOrigen().getCodigo());
            edge.to = graph.nodes.get(v.getAeropuertoDestino().getCodigo());

            edge.capacity = v.getCapacidad() != null ? v.getCapacidad() : 0;

            // Normalizar salida y llegada a UTC restando el offset de cada aeropuerto.
            // Los archivos de datos usan hora LOCAL en cada aeropuerto; para que el Dijkstra
            // compare tiempos de forma coherente entre continentes todo debe estar en UTC.
            // minusHours(offset): para GMT-5 → minus(-5) = +5h; para GMT+2 → minus(+2) = -2h.
            int originOffset = v.getAeropuertoOrigen().getOffsetHorario();
            int destOffset   = v.getAeropuertoDestino().getOffsetHorario();

            LocalDateTime depUtc = v.getFechaHoraSalida().minusHours(originOffset);

            // La duración se calcula SOLO desde las horas de pared y los husos, con un único
            // módulo 24h. No se deriva de fechaHoraLlegada porque el cruce de medianoche no se
            // puede decidir comparando horas de pared de aeropuertos en husos distintos: un vuelo
            // hacia el oeste más corto que su diferencia de huso aterriza a una hora de pared menor
            // que la de salida sin cruzar medianoche, lo que inflaba la duración en 24h.
            int depWall = v.getFechaHoraSalida().getHour() * 60 + v.getFechaHoraSalida().getMinute();
            int arrWall = v.getFechaHoraLlegada().getHour() * 60 + v.getFechaHoraLlegada().getMinute();
            int durMin = Math.floorMod((arrWall - destOffset * 60) - (depWall - originOffset * 60), 1440);

            LocalDateTime arrUtc = depUtc.plusMinutes(durMin);
            Duration utcDur = Duration.ofMinutes(durMin);

            edge.departureTime     = depUtc;
            edge.arrivalTime       = arrUtc;
            edge.duration          = utcDur;
            edge.cost              = durMin;
            edge.departureLocalTime = depUtc.toLocalTime();
            edge.durationMinutes   = durMin;
            edge.depMinuteOfDay    = depUtc.getHour() * 60 + depUtc.getMinute();
            edge.idx               = edgeIdx++;

            graph.addEdge(edge);
        }

        return graph;
    }

    /**
     * Convierte la lista masiva de maletas en lotes (Batches) para el algoritmo.
     */
    public List<LuggageBatch> mapToBatches(List<Maleta> maletas) {
        return maletas.stream().map(m -> {
            // Normalizar readyTime a UTC restando el offset del aeropuerto origen.
            // Los archivos de envíos usan hora local; el Dijkstra opera en UTC.
            int offset = m.getAeropuertoOrigen().getOffsetHorario();
            LocalDateTime readyTimeUtc = m.getFechaHoraRegistro().minusHours(offset);
            LuggageBatch b = new LuggageBatch(
                    m.getIdEnvio(),
                    m.getCantidad(),
                    m.getPlazo(),
                    m.getAeropuertoOrigen().getCodigo(),
                    m.getAeropuertoDestino().getCodigo(),
                    readyTimeUtc
            );
            if (m.getCliente() != null) b.setClienteId(m.getCliente().getId());
            return b;
        }).collect(Collectors.toList());
    }
}
