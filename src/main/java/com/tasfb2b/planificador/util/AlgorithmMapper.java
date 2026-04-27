package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import java.time.Duration;
import java.time.LocalDateTime;
import com.tasfb2b.planificador.algorithm.aco.Graph;
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
            graph.nodes.get(a.getCodigo()).capacity = a.getCapacidad();
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

            edge.capacity = v.getCapacidad();

            // Normalizar salida y llegada a UTC restando el offset de cada aeropuerto.
            // Los archivos de datos usan hora LOCAL en cada aeropuerto; para que el Dijkstra
            // compare tiempos de forma coherente entre continentes todo debe estar en UTC.
            // minusHours(offset): para GMT-5 → minus(-5) = +5h; para GMT+2 → minus(+2) = -2h.
            int originOffset = v.getAeropuertoOrigen().getOffsetHorario();
            int destOffset   = v.getAeropuertoDestino().getOffsetHorario();

            LocalDateTime depUtc = v.getFechaHoraSalida().minusHours(originOffset);
            LocalDateTime arrUtc = v.getFechaHoraLlegada().minusHours(destOffset);

            Duration utcDur = Duration.between(depUtc, arrUtc);
            if (utcDur.isNegative() || utcDur.isZero()) utcDur = utcDur.plusDays(1);

            edge.departureTime     = depUtc;
            edge.arrivalTime       = arrUtc;
            edge.duration          = utcDur;
            edge.cost              = utcDur.toMinutes();
            edge.departureLocalTime = depUtc.toLocalTime();
            edge.durationMinutes   = (int) utcDur.toMinutes();
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
            return new LuggageBatch(
                    m.getIdEnvio(),
                    m.getCantidad(),
                    m.getPlazo(),
                    m.getAeropuertoOrigen().getCodigo(),
                    m.getAeropuertoDestino().getCodigo(),
                    readyTimeUtc
            );
        }).collect(Collectors.toList());
    }
}