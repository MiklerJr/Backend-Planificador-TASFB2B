package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.algorithm.grafo.Edge;
import java.time.Duration;
import java.time.LocalDateTime;
import com.tasfb2b.planificador.algorithm.grafo.Graph;
import com.tasfb2b.planificador.algorithm.grafo.Node;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.model.dataset.Aeropuerto;
import com.tasfb2b.planificador.model.dataset.Envio;
import com.tasfb2b.planificador.model.dataset.Vuelo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AlgorithmMapper {


    public Graph mapToGraph(List<Aeropuerto> aeropuertos, List<Vuelo> vuelos) {
        Graph graph = new Graph();

        // 1. Mapear Nodos (Aeropuertos)
        for (Aeropuerto a : aeropuertos) {
            graph.addNode(a.getCodigo());
            Node nodo = graph.nodes.get(a.getCodigo());
            int capacidadAlmacen = a.getCapacidad() != null ? a.getCapacidad() : 0;
            nodo.capacity = capacidadAlmacen;
            nodo.storageCapacity = capacidadAlmacen;
        }

        // 2. Mapear Aristas (Vuelos)
        int edgeIdx = 0;
        for (Vuelo v : vuelos) {
            Edge edge = new Edge();

            if (v.getId() != null) {
                edge.id = v.getId().toString();
            } else {
                edge.id = v.getAeropuertoOrigen().getCodigo() + "-" +
                        v.getAeropuertoDestino().getCodigo() + "-" +
                        v.getFechaHoraSalida().toLocalTime().toString();
            }

            edge.from = graph.nodes.get(v.getAeropuertoOrigen().getCodigo());
            edge.to = graph.nodes.get(v.getAeropuertoDestino().getCodigo());

            edge.capacity = v.getCapacidad() != null ? v.getCapacidad() : 0;

            int originOffset = v.getAeropuertoOrigen().getOffsetHorario();
            int destOffset   = v.getAeropuertoDestino().getOffsetHorario();

            LocalDateTime depUtc = v.getFechaHoraSalida().minusHours(originOffset);

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

    public List<LuggageBatch> mapToBatches(List<Envio> maletas) {
        return maletas.stream().map(m -> {

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
