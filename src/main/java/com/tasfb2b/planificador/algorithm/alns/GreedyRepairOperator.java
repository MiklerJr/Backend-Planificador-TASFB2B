package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import java.time.LocalDateTime;
import java.util.*;

public class GreedyRepairOperator implements RepairOperator {

    private Graph graph;

    public GreedyRepairOperator(Graph graph) {
        this.graph = graph;
    }

    @Override
    public void repair(AlnsSolution solution, List<LuggageBatch> unassigned) {
        for (LuggageBatch batch : unassigned) {
            // Le pasamos 'solution' como segundo parámetro
            List<Edge> bestRoute = findShortestPath(batch, solution);
            batch.setAssignedRoute(bestRoute);
        }
    }

    // Agregamos 'AlnsSolution solution' a los parámetros que recibe el método
    private List<Edge> findShortestPath(LuggageBatch batch, AlnsSolution solution) {
        String startNode = batch.getOriginCode();
        String targetNode = batch.getDestCode();
        LocalDateTime readyTime = batch.getReadyTime();

        // Cola de prioridad para explorar siempre el camino que llega más temprano
        PriorityQueue<RouteState> pq = new PriorityQueue<>(Comparator.comparing(state -> state.currentTime));

        // Mapa para evitar ciclos infinitos (guarda la hora más temprana en la que llegamos a un nodo)
        Map<String, LocalDateTime> bestArrivalTimes = new HashMap<>();

        // Estado inicial
        pq.add(new RouteState(startNode, readyTime, new ArrayList<>()));
        bestArrivalTimes.put(startNode, readyTime);

        while (!pq.isEmpty()) {
            RouteState current = pq.poll();

            // Si llegamos al destino, retornamos la ruta
            if (current.nodeCode.equals(targetNode)) {
                return current.route;
            }

            // Explorar vuelos que salen desde el nodo actual
            for (Edge flight : graph.getNeighbors(current.nodeCode)) {

                // Regla de tiempo: El vuelo debe salir al menos 10 minutos DESPUÉS del currentTime
                // (Si es el primer vuelo desde el origen, el currentTime es el readyTime de la maleta)
                boolean isFirstFlight = current.route.isEmpty();
                long minWaitTime = isFirstFlight ? 0 : 10;

                // Solo consideramos el vuelo si hay espacio para TODO el lote
                // (Ahora 'solution' es reconocida sin problemas)
                if (getRemainingCapacity(flight, solution) < batch.getQuantity()) {
                    continue;
                }

                LocalDateTime minDepartureAllowed = current.currentTime.plusMinutes(minWaitTime);

                if (!flight.departureTime.isBefore(minDepartureAllowed)) {

                    // Comprobar si este vuelo nos hace llegar al siguiente nodo más rápido que antes
                    LocalDateTime arrivalAtNext = flight.arrivalTime;
                    String nextNode = flight.to.code;

                    if (!bestArrivalTimes.containsKey(nextNode) || arrivalAtNext.isBefore(bestArrivalTimes.get(nextNode))) {

                        bestArrivalTimes.put(nextNode, arrivalAtNext);

                        // Crear la nueva ruta agregando este vuelo
                        List<Edge> newRoute = new ArrayList<>(current.route);
                        newRoute.add(flight);

                        pq.add(new RouteState(nextNode, arrivalAtNext, newRoute));
                    }
                }
            }
        }

        // Si la cola se vacía y no encontramos el targetNode, no hay ruta posible
        return new ArrayList<>();
    }

    // Clase auxiliar interna para manejar el estado de la búsqueda
    private static class RouteState {
        String nodeCode;
        LocalDateTime currentTime;
        List<Edge> route;

        public RouteState(String nodeCode, LocalDateTime currentTime, List<Edge> route) {
            this.nodeCode = nodeCode;
            this.currentTime = currentTime;
            this.route = route;
        }
    }

    private int getRemainingCapacity(Edge flight, AlnsSolution solution) {
        int occupied = solution.getBatches().stream()
                .filter(b -> b.getAssignedRoute() != null && b.getAssignedRoute().contains(flight))
                .mapToInt(LuggageBatch::getQuantity)
                .sum();
        return (int) flight.capacity - occupied;
    }
}