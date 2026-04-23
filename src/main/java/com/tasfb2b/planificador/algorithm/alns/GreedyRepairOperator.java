package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

public class GreedyRepairOperator implements RepairOperator {

    private final Graph graph;

    // Clave: flightId + "_" + fecha (ej. "SKBO-SPIM-06:00_2026-03-15")
    // Cada día tiene su propia capacidad independiente, porque es una instancia distinta del vuelo.
    private final Map<String, Integer> flightOccupancy = new HashMap<>();

    public GreedyRepairOperator(Graph graph) {
        this.graph = graph;
    }

    @Override
    public void repair(AlnsSolution solution, List<LuggageBatch> unassigned) {
        for (LuggageBatch batch : unassigned) {
            RouteResult result = findShortestPath(batch);
            batch.setAssignedRoute(result.edges);
            for (int i = 0; i < result.edges.size(); i++) {
                String key = result.edges.get(i).id + "_" + result.actualDepartures.get(i).toLocalDate();
                flightOccupancy.merge(key, batch.getQuantity(), Integer::sum);
            }
        }
    }

    private RouteResult findShortestPath(LuggageBatch batch) {
        String startNode  = batch.getOriginCode();
        String targetNode = batch.getDestCode();
        LocalDateTime readyTime = batch.getReadyTime();

        PriorityQueue<RouteState> pq = new PriorityQueue<>(Comparator.comparing(s -> s.currentTime));
        Map<String, LocalDateTime> bestArrivalTimes = new HashMap<>();

        pq.add(new RouteState(startNode, readyTime, new ArrayList<>(), new ArrayList<>()));
        bestArrivalTimes.put(startNode, readyTime);

        while (!pq.isEmpty()) {
            RouteState current = pq.poll();

            if (current.nodeCode.equals(targetNode)) {
                return new RouteResult(current.route, current.actualDepartures);
            }

            for (Edge flight : graph.getNeighbors(current.nodeCode)) {
                // Tiempo mínimo de espera: 0 min en el origen, 10 min de escala en conexiones
                long minWait = current.route.isEmpty() ? 0L : 10L;
                LocalDateTime minDeparture = current.currentTime.plusMinutes(minWait);

                // Próxima ocurrencia diaria del vuelo a partir de minDeparture
                LocalDateTime actualDeparture = nextDeparture(flight.departureTime.toLocalTime(), minDeparture);
                LocalDateTime actualArrival   = actualDeparture.plus(flightDuration(flight));

                // Capacidad por instancia diaria del vuelo
                String capacityKey = flight.id + "_" + actualDeparture.toLocalDate();
                if (getRemainingCapacity(flight, capacityKey) < batch.getQuantity()) continue;

                String nextNode = flight.to.code;
                if (!bestArrivalTimes.containsKey(nextNode)
                        || actualArrival.isBefore(bestArrivalTimes.get(nextNode))) {

                    bestArrivalTimes.put(nextNode, actualArrival);

                    List<Edge> newRoute       = new ArrayList<>(current.route);
                    List<LocalDateTime> newDep = new ArrayList<>(current.actualDepartures);
                    newRoute.add(flight);
                    newDep.add(actualDeparture);

                    pq.add(new RouteState(nextNode, actualArrival, newRoute, newDep));
                }
            }
        }

        return RouteResult.EMPTY;
    }

    // Dado un horario de salida (hora:min) y el instante más temprano permitido,
    // devuelve la próxima ocurrencia del vuelo: hoy si todavía no pasó, mañana si ya pasó.
    private LocalDateTime nextDeparture(LocalTime flightTime, LocalDateTime earliest) {
        LocalDateTime candidate = LocalDateTime.of(earliest.toLocalDate(), flightTime);
        return candidate.isBefore(earliest) ? candidate.plusDays(1) : candidate;
    }

    // Duración real del vuelo a partir de las horas base almacenadas en el Edge.
    // Si la llegada está antes que la salida (vuelo que cruza medianoche), suma un día.
    private Duration flightDuration(Edge flight) {
        Duration d = Duration.between(flight.departureTime, flight.arrivalTime);
        return d.isNegative() ? d.plusDays(1) : d;
    }

    private int getRemainingCapacity(Edge flight, String capacityKey) {
        return (int) flight.capacity - flightOccupancy.getOrDefault(capacityKey, 0);
    }

    private static class RouteState {
        final String nodeCode;
        final LocalDateTime currentTime;
        final List<Edge> route;
        final List<LocalDateTime> actualDepartures;

        RouteState(String nodeCode, LocalDateTime currentTime,
                   List<Edge> route, List<LocalDateTime> actualDepartures) {
            this.nodeCode         = nodeCode;
            this.currentTime      = currentTime;
            this.route            = route;
            this.actualDepartures = actualDepartures;
        }
    }

    private static class RouteResult {
        final List<Edge> edges;
        final List<LocalDateTime> actualDepartures;

        static final RouteResult EMPTY = new RouteResult(Collections.emptyList(), Collections.emptyList());

        RouteResult(List<Edge> edges, List<LocalDateTime> actualDepartures) {
            this.edges            = edges;
            this.actualDepartures = actualDepartures;
        }
    }
}
