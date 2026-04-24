package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class GreedyRepairOperator implements RepairOperator {

    private static final long CONNECTION_MIN   = 10L;
    private static final long DEST_STORAGE_MIN = 10L;
    private static final long MAX_HORIZON_MIN  = 3 * 24 * 60L;
    private static final long DAY_MIN          = FlightKeyEncoder.DAY_MIN;
    private static final int  DAY_BITS         = FlightKeyEncoder.DAY_BITS;

    private final Graph graph;

    private final int      nodeCount;
    private static final int DAY_SLOTS = (int)(MAX_HORIZON_MIN / DAY_MIN) + 1; // 4

    private final Edge[]       edgeByIdx;
    private final String[]     nodeByIdx;
    private final List<Edge>[] adjByIdx;   // adjByIdx[node.idx] → vecinos salientes

    // Ocupación global: suma de todos los bloques ya confirmados.
    // Solo se escribe mediante commitBlock(); lecturas concurrentes desde Dijkstra son seguras.
    private final ConcurrentHashMap<Long, Integer> flightOccupancy  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> airportOccupancy = new ConcurrentHashMap<>();

    // Vuelos cancelados: flightKeys con capacidad efectiva = 0.
    private Set<Long> cancelledFlightDays = Collections.emptySet();

    public GreedyRepairOperator(Graph graph) {
        this.graph = graph;

        // Asignar idx entero a cada nodo (una sola vez)
        Map<String, Integer> nodeIndex = new HashMap<>(graph.nodes.size() * 2);
        int i = 0;
        for (Map.Entry<String, Node> entry : graph.nodes.entrySet()) {
            nodeIndex.put(entry.getKey(), i);
            entry.getValue().idx = i;
            i++;
        }
        nodeCount = i;

        nodeByIdx = new String[nodeCount];
        for (Map.Entry<String, Integer> e : nodeIndex.entrySet()) nodeByIdx[e.getValue()] = e.getKey();

        int maxIdx = -1;
        for (Edge e : graph.edges) if (e.idx > maxIdx) maxIdx = e.idx;
        edgeByIdx = new Edge[maxIdx + 1];
        for (Edge e : graph.edges) edgeByIdx[e.idx] = e;

        // Lista de adyacencia indexada por node.idx (evita HashMap lookup en inner loop)
        @SuppressWarnings("unchecked")
        List<Edge>[] adj = new List[nodeCount];
        for (int j = 0; j < nodeCount; j++) adj[j] = new ArrayList<>();
        for (Edge e : graph.edges) {
            if (e.from != null && e.from.idx >= 0) adj[e.from.idx].add(e);
        }
        adjByIdx = adj;
    }

    // -----------------------------------------------------------------------
    // Interface RepairOperator
    // -----------------------------------------------------------------------

    @Override
    public void repair(AlnsSolution solution, List<LuggageBatch> unassigned,
                       Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        for (LuggageBatch batch : unassigned) {
            RouteResult result = findShortestPath(batch, blockFlight, blockAirport);
            batch.setAssignedRoute(result.edges);
            batch.setAssignedDepartures(result.actualDepartures);
            batch.setCumpleSLA(result.cumpleSLA);
            applyToBlock(batch, result, blockFlight, blockAirport);
        }
    }

    // -----------------------------------------------------------------------
    // Métodos de gestión de ocupación por bloque
    // -----------------------------------------------------------------------

    /** Descuenta la ocupación de un batch de los mapas del bloque (para fase destroy). */
    public void releaseFromBlock(LuggageBatch batch,
                                  Map<Long, Integer> blockFlight,
                                  Map<Long, Integer> blockAirport) {
        List<Edge> route = batch.getAssignedRoute();
        List<Long> deps  = batch.getAssignedDepartures();
        if (route == null || route.isEmpty() || deps == null || deps.isEmpty()) return;

        for (int i = 0; i < route.size(); i++) {
            Edge e      = route.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.durationMinutes;

            blockFlight.merge(flightKey(e.idx, depMin), -batch.getQuantity(), Integer::sum);

            boolean esFinalLeg = (i == route.size() - 1);
            if (!esFinalLeg && e.to.idx >= 0) {
                long arrDay     = arrMin / DAY_MIN;
                long nextDepMin = deps.get(i + 1);
                long depDay     = nextDepMin / DAY_MIN;
                for (long day = arrDay; day <= depDay; day++) {
                    blockAirport.merge(airportKey(e.to.idx, day * DAY_MIN),
                            -batch.getQuantity(), Integer::sum);
                }
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                blockAirport.merge(airportKey(e.to.idx, arrMin), -batch.getQuantity(), Integer::sum);
            }
        }
    }

    /** Registra qué vuelo-días están cancelados (capacidad efectiva = 0). */
    public void setCancelledFlights(Set<Long> cancelled) {
        this.cancelledFlightDays = cancelled == null ? Collections.emptySet() : cancelled;
    }

    /** Confirma los mapas del bloque en la ocupación global al finalizar el bloque. */
    public void commitBlock(Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        blockFlight.forEach((key, qty) -> {
            if (qty != 0) flightOccupancy.merge(key, qty, Integer::sum);
        });
        blockAirport.forEach((key, qty) -> {
            if (qty != 0) airportOccupancy.merge(key, qty, Integer::sum);
        });
    }

    // -----------------------------------------------------------------------
    // Dijkstra earliest-arrival con capacidad global + bloque
    // -----------------------------------------------------------------------

    private RouteResult findShortestPath(LuggageBatch batch,
                                          Map<Long, Integer> blockFlight,
                                          Map<Long, Integer> blockAirport) {
        Node startNodeObj  = graph.nodes.get(batch.getOriginCode());
        Node targetNodeObj = graph.nodes.get(batch.getDestCode());
        if (startNodeObj == null || targetNodeObj == null) return RouteResult.EMPTY;

        int startIdx      = startNodeObj.idx;
        int targetNodeIdx = targetNodeObj.idx;
        if (startIdx < 0 || targetNodeIdx < 0) return RouteResult.EMPTY;

        long readyMin      = toEpochMin(batch.getReadyTime());
        long readyDay      = readyMin / DAY_MIN;
        long slaMaxMinutes = (long) batch.getSlaLimitHours() * 60;

        long[] bestTimes = new long[nodeCount * DAY_SLOTS];
        Arrays.fill(bestTimes, Long.MAX_VALUE);

        PriorityQueue<RouteState> pq = new PriorityQueue<>(Comparator.comparingLong(s -> s.arrivalMin));

        long horizonDays = MAX_HORIZON_MIN / DAY_MIN;
        for (long d = 0; d <= horizonDays; d++) {
            long startMin = readyMin + d * DAY_MIN;
            bestTimes[startIdx * DAY_SLOTS + (int)d] = startMin;
            pq.add(new RouteState(startIdx, startMin, -1L, null, null));
        }

        while (!pq.isEmpty()) {
            RouteState current = pq.poll();

            if (current.nodeIdx == targetNodeIdx) {
                List<Edge> edges = new ArrayList<>();
                List<Long> deps  = new ArrayList<>();
                for (RouteState s = current; s.edge != null; s = s.parent) {
                    edges.add(0, s.edge);
                    deps.add(0, s.depMin);
                }
                long transitMinutes = (current.arrivalMin + DEST_STORAGE_MIN) - readyMin;
                return new RouteResult(edges, deps, transitMinutes <= slaMaxMinutes);
            }

            for (Edge flight : adjByIdx[current.nodeIdx]) {
                long minWait  = (current.edge == null) ? 0L : CONNECTION_MIN;
                long earliest = current.arrivalMin + minWait;

                long actualDep = nextDepartureMin(flight.depMinuteOfDay, earliest);
                long actualArr = actualDep + flight.durationMinutes;

                long dayOffset = actualArr / DAY_MIN - readyDay;
                if (dayOffset < 0 || dayOffset >= DAY_SLOTS) continue;
                if (actualArr - readyMin > MAX_HORIZON_MIN) continue;

                // Capacidad del vuelo (global + bloque)
                if (remainingFlight(flight, actualDep, blockFlight) < batch.getQuantity()) continue;

                // Capacidad de almacén: todas las maletas ingresan al almacén al aterrizar,
                // sea escala o destino final (enunciado: "Sea que hagan escala o sea que
                // esté en su destino final").
                int nextIdx = flight.to.idx;
                if (nextIdx < 0) continue;
                if (flight.to.capacity > 0) {
                    int qty = batch.getQuantity();
                    int cap = flight.to.capacity;
                    long ak = airportKey(nextIdx, actualArr);
                    if (airportOccupancy.getOrDefault(ak, 0) + blockAirport.getOrDefault(ak, 0) + qty > cap)
                        continue;
                    // Escala intermedia: verificar también el día siguiente (estadía overnight).
                    // Destino final: la maleta sale en DEST_STORAGE_MIN (10 min) → no overnight.
                    if (nextIdx != targetNodeIdx) {
                        long akD1 = airportKey(nextIdx, actualArr + DAY_MIN);
                        if (airportOccupancy.getOrDefault(akD1, 0) + blockAirport.getOrDefault(akD1, 0) + qty > cap)
                            continue;
                    }
                }

                int cell = nextIdx * DAY_SLOTS + (int)dayOffset;
                if (actualArr < bestTimes[cell]) {
                    bestTimes[cell] = actualArr;
                    pq.add(new RouteState(nextIdx, actualArr, actualDep, flight, current));
                }
            }
        }

        return RouteResult.EMPTY;
    }

    // -----------------------------------------------------------------------
    // Helpers privados
    // -----------------------------------------------------------------------

    private void applyToBlock(LuggageBatch batch, RouteResult result,
                               Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        for (int i = 0; i < result.edges.size(); i++) {
            Edge e      = result.edges.get(i);
            long depMin = result.actualDepartures.get(i);
            long arrMin = depMin + e.durationMinutes;

            blockFlight.merge(flightKey(e.idx, depMin), batch.getQuantity(), Integer::sum);

            boolean esFinalLeg = (i == result.edges.size() - 1);
            if (!esFinalLeg && e.to.idx >= 0) {
                long arrDay     = arrMin / DAY_MIN;
                long nextDepMin = result.actualDepartures.get(i + 1);
                long depDay     = nextDepMin / DAY_MIN;
                for (long day = arrDay; day <= depDay; day++) {
                    blockAirport.merge(airportKey(e.to.idx, day * DAY_MIN),
                            batch.getQuantity(), Integer::sum);
                }
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                // Destino final: la maleta entra al almacén al aterrizar (10 min, mismo día).
                blockAirport.merge(airportKey(e.to.idx, arrMin), batch.getQuantity(), Integer::sum);
            }
        }
    }

    private long nextDepartureMin(int depMinuteOfDay, long earliest) {
        long dayStart  = (earliest / DAY_MIN) * DAY_MIN;
        long candidate = dayStart + depMinuteOfDay;
        return candidate < earliest ? candidate + DAY_MIN : candidate;
    }

    private int remainingFlight(Edge flight, long depMin, Map<Long, Integer> blockFlight) {
        long key = flightKey(flight.idx, depMin);
        if (cancelledFlightDays.contains(key)) return 0;  // vuelo cancelado ese día
        return flight.capacity
             - flightOccupancy.getOrDefault(key, 0)
             - blockFlight.getOrDefault(key, 0);
    }

    private static long flightKey(int edgeIdx, long epochMin) {
        return FlightKeyEncoder.flightKey(edgeIdx, epochMin);
    }

    private static long airportKey(int nodeIdx, long epochMin) {
        return FlightKeyEncoder.airportKey(nodeIdx, epochMin);
    }

    private static long toEpochMin(LocalDateTime dt) {
        return dt.toLocalDate().toEpochDay() * DAY_MIN + dt.getHour() * 60L + dt.getMinute();
    }

    // -----------------------------------------------------------------------
    // Diagnóstico
    // -----------------------------------------------------------------------

    public void logEstadisticasCapacidad() {
        log.info("--- Capacidad de vuelos ---");
        long flightDaysUsados = flightOccupancy.size();
        long flightDaysLlenos = 0, flightDaysSobre = 0, totalAsignado = 0, totalCapacidad = 0;
        List<String> sobre = new ArrayList<>();

        for (Map.Entry<Long, Integer> entry : flightOccupancy.entrySet()) {
            int edgeIdx  = (int)(entry.getKey() >> DAY_BITS);
            int asignado = entry.getValue();
            totalAsignado += asignado;
            if (edgeIdx < edgeByIdx.length && edgeByIdx[edgeIdx] != null) {
                int cap = edgeByIdx[edgeIdx].capacity;
                totalCapacidad += cap;
                if (asignado >= cap) flightDaysLlenos++;
                if (asignado > cap) {
                    flightDaysSobre++;
                    sobre.add(edgeByIdx[edgeIdx].id + "=" + asignado + "/" + cap);
                }
            }
        }
        log.info("  Flight-days con ocupación   : {}", flightDaysUsados);
        log.info("  Flight-days al 100 %         : {}", flightDaysLlenos);
        log.info("  Flight-days sobre capacidad  : {}", flightDaysSobre);
        if (totalCapacidad > 0)
            log.info("  Utilización global           : {}/{} ({} %)",
                    totalAsignado, totalCapacidad, totalAsignado * 100 / totalCapacidad);
        if (!sobre.isEmpty()) {
            log.warn("  Ejemplos sobre capacidad (race condition en paralelo):");
            sobre.stream().limit(5).forEach(s -> log.warn("    {}", s));
        }

        log.info("--- Capacidad de aeropuertos (almacén) ---");
        long airportDaysUsados = airportOccupancy.size();
        long airportDaysLlenos = 0, airportDaysSobre = 0, totalAirportAsig = 0, totalAirportCap = 0;
        Map<String, long[]> porAero = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : airportOccupancy.entrySet()) {
            int    nodeIdx  = (int)(entry.getKey() >> DAY_BITS);
            int    asignado = entry.getValue();
            totalAirportAsig += asignado;
            String code = (nodeIdx < nodeByIdx.length) ? nodeByIdx[nodeIdx] : "?";
            Node nodo   = graph.nodes.get(code);
            int cap = (nodo != null && nodo.capacity > 0) ? nodo.capacity : -1;
            long[] s = porAero.computeIfAbsent(code, k -> new long[2]);
            s[0] += asignado;
            if (cap > 0) {
                s[1] = cap;
                totalAirportCap += cap;
                if (asignado >= cap) airportDaysLlenos++;
                if (asignado > cap)  airportDaysSobre++;
            }
        }
        log.info("  Airport-days con ocupación  : {}", airportDaysUsados);
        log.info("  Airport-days al 100 %        : {}", airportDaysLlenos);
        log.info("  Airport-days sobre capacidad : {}", airportDaysSobre);
        if (totalAirportCap > 0)
            log.info("  Utilización global aerop.    : {}/{} ({} %)",
                    totalAirportAsig, totalAirportCap, totalAirportAsig * 100 / totalAirportCap);
        log.info("  Top aeropuertos por ocupación total:");
        porAero.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(10)
                .forEach(e -> {
                    long asig = e.getValue()[0], cap = e.getValue()[1];
                    log.info("    {} | total_transit={}{}", e.getKey(), asig,
                            cap > 0 ? " (cap/día=" + cap + ")" : "");
                });
    }

    // -----------------------------------------------------------------------
    // Clases internas
    // -----------------------------------------------------------------------

    private static class RouteState {
        final int        nodeIdx;
        final long       arrivalMin;
        final long       depMin;
        final Edge       edge;
        final RouteState parent;

        RouteState(int nodeIdx, long arrivalMin, long depMin, Edge edge, RouteState parent) {
            this.nodeIdx    = nodeIdx;
            this.arrivalMin = arrivalMin;
            this.depMin     = depMin;
            this.edge       = edge;
            this.parent     = parent;
        }
    }

    private static class RouteResult {
        final List<Edge> edges;
        final List<Long> actualDepartures;
        final boolean    cumpleSLA;

        static final RouteResult EMPTY =
                new RouteResult(Collections.emptyList(), Collections.emptyList(), false);

        RouteResult(List<Edge> edges, List<Long> actualDepartures, boolean cumpleSLA) {
            this.edges            = edges;
            this.actualDepartures = actualDepartures;
            this.cumpleSLA        = cumpleSLA;
        }
    }
}
