package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.algorithm.aco.Edge;
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
            // Asumiendo que tu Graph tiene un método para añadir nodos por código
            graph.addNode(a.getCodigo());
        }

        // 2. Mapear Aristas (Vuelos)
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
            edge.departureTime = v.getFechaHoraSalida();
            edge.arrivalTime = v.getFechaHoraLlegada();

            // Calcular el 'cost' en minutos de este vuelo
            edge.cost = java.time.Duration.between(edge.departureTime, edge.arrivalTime).toMinutes();

            graph.addEdge(edge);
        }

        return graph;
    }

    /**
     * Convierte la lista masiva de maletas en lotes (Batches) para el algoritmo.
     */
    public List<LuggageBatch> mapToBatches(List<Maleta> maletas) {
        return maletas.stream().map(m -> {
            return new LuggageBatch(
                    m.getIdEnvio(),
                    m.getCantidad(), // Usamos el campo cantidad que agregamos antes
                    m.getPlazo(),
                    m.getAeropuertoOrigen().getCodigo(),
                    m.getAeropuertoDestino().getCodigo(),
                    m.getFechaHoraRegistro()
            );
        }).collect(Collectors.toList());
    }
}