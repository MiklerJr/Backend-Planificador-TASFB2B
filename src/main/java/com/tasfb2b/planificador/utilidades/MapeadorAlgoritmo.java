package com.tasfb2b.planificador.utilidades;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import java.time.Duration;
import java.time.LocalDateTime;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Envio;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MapeadorAlgoritmo {


    public Grafo mapToGraph(List<Aeropuerto> aeropuertos, List<Vuelo> vuelos) {
        Grafo graph = new Grafo();

        // 1. Mapear Nodos (Aeropuertos)
        for (Aeropuerto a : aeropuertos) {
            graph.addNode(a.getCodigo());
            Nodo nodo = graph.nodes.get(a.getCodigo());
            int capacidadAlmacen = a.getCapacidad() != null ? a.getCapacidad() : 0;
            nodo.capacity = capacidadAlmacen;
            nodo.storageCapacity = capacidadAlmacen;
        }

        // 2. Mapear Aristas (Vuelos)
        int edgeIdx = 0;
        for (Vuelo v : vuelos) {
            Arista edge = new Arista();

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

    public List<LoteEnvio> mapToBatches(List<Envio> maletas) {
        return maletas.stream().map(m -> {

            int offset = m.getAeropuertoOrigen().getOffsetHorario();
            LocalDateTime readyTimeUtc = m.getFechaHoraRegistro().minusHours(offset);
            LoteEnvio b = new LoteEnvio(
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
