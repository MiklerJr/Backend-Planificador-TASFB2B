package com.tasfb2b.planificador.algorithm.alns;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class GreedyRepairOperator implements RepairOperator {

    // Restricciones operativas parametrizadas
    private static final long CONNECTION_MIN   = 10L;       // escala mínima entre vuelos
    private static final long DEST_STORAGE_MIN = 10L;       // permanencia en almacén de destino
    private static final long MAX_HORIZON_MIN  = 3 * 24 * 60L; // horizonte máximo de búsqueda (3 días)

    private final Graph graph;

    // Índice nodo→entero para usar array en vez de HashMap en findShortestPath.
    // Construido una vez en el constructor; el orden no importa, solo la unicidad.
    private final Map<String, Integer> nodeIndex;
    private final int nodeCount;
    private static final int DAY_SLOTS = (int)(MAX_HORIZON_MIN / (24 * 60)) + 1; // 4

    // ConcurrentHashMap permite que varios hilos lean/escriban sin bloquear el algoritmo completo.
    // merge() en ConcurrentHashMap es atómico: no hay riesgo de corrupción de datos.
    // Nota: la comprobación de capacidad (read → check → write) no es estrictamente atómica,
    // lo que puede producir leves sobreasignaciones en condiciones de alta concurrencia.
    // Para una simulación de planificación esto es aceptable.
    private final ConcurrentHashMap<String, Integer> flightOccupancy  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> airportOccupancy = new ConcurrentHashMap<>();

    public GreedyRepairOperator(Graph graph) {
        this.graph = graph;
        nodeIndex  = new HashMap<>(graph.nodes.size() * 2);
        int i = 0;
        for (String code : graph.nodes.keySet()) nodeIndex.put(code, i++);
        nodeCount = nodeIndex.size();
    }

    @Override
    public void repair(AlnsSolution solution, List<LuggageBatch> unassigned) {
        for (LuggageBatch batch : unassigned) {
            RouteResult result = findShortestPath(batch);
            batch.setAssignedRoute(result.edges);
            batch.setCumpleSLA(result.cumpleSLA);
            for (int i = 0; i < result.edges.size(); i++) {
                Edge e   = result.edges.get(i);
                LocalDateTime dep = result.actualDepartures.get(i);
                LocalDateTime arr = dep.plus(e.duration);

                // Capacidad consumida en el vuelo
                flightOccupancy.merge(e.id + "_" + dep.toLocalDate(),
                        batch.getQuantity(), Integer::sum);

                // Capacidad de almacén solo para escalas intermedias: la maleta que llega
                // a su destino final sale del sistema y no ocupa almacén de tránsito.
                boolean esFinalLeg = (i == result.edges.size() - 1);
                if (!esFinalLeg) {
                    airportOccupancy.merge(e.to.code + "_" + arr.toLocalDate(),
                            batch.getQuantity(), Integer::sum);
                }
            }
        }
    }

    private RouteResult findShortestPath(LuggageBatch batch) {
        String startNode  = batch.getOriginCode();
        String targetNode = batch.getDestCode();
        LocalDateTime readyTime    = batch.getReadyTime();
        long slaMaxMinutes = (long) batch.getSlaLimitHours() * 60;

        Integer startIdx = nodeIndex.get(startNode);
        if (startIdx == null) return RouteResult.EMPTY;

        // Array compacto en vez de HashMap<String,LocalDateTime>: elimina String-key efímeros.
        // Dimensión: nodeCount × DAY_SLOTS (30 × 4 = 120 celdas; null = no visitado aún).
        LocalDateTime[] bestTimes = new LocalDateTime[nodeCount * DAY_SLOTS];
        long readyEpoch = readyTime.toLocalDate().toEpochDay();

        PriorityQueue<RouteState> pq = new PriorityQueue<>(Comparator.comparing(s -> s.currentTime));

        // Pre-cargamos el origen para cada día del horizonte.
        // Si todos los intermedios del día 0 están llenos, el algoritmo
        // intenta partir en día 1, 2 ó 3 y aún enruta la maleta (posiblemente tardada).
        long horizonDays = MAX_HORIZON_MIN / (24 * 60);
        for (long d = 0; d <= horizonDays; d++) {
            LocalDateTime startTime = readyTime.plusDays(d);
            bestTimes[startIdx * DAY_SLOTS + (int)d] = startTime;
            pq.add(new RouteState(startNode, startTime, null, null, null));
        }

        while (!pq.isEmpty()) {
            RouteState current = pq.poll();

            if (current.nodeCode.equals(targetNode)) {
                // Reconstruir ruta desde la cadena de padres (O(hops), sin copias durante exploración)
                List<Edge> edges       = new ArrayList<>();
                List<LocalDateTime> deps = new ArrayList<>();
                for (RouteState s = current; s.edge != null; s = s.parent) {
                    edges.add(0, s.edge);
                    deps.add(0, s.departure);
                }
                long transitMinutes = Duration.between(readyTime,
                        current.currentTime.plusMinutes(DEST_STORAGE_MIN)).toMinutes();
                return new RouteResult(edges, deps, transitMinutes <= slaMaxMinutes);
            }

            for (Edge flight : graph.getNeighbors(current.nodeCode)) {
                // 0 min en origen (sin arista previa), CONNECTION_MIN en escalas intermedias.
                long minWait = (current.edge == null) ? 0L : CONNECTION_MIN;
                LocalDateTime minDeparture = current.currentTime.plusMinutes(minWait);

                // flight.departureLocalTime y flight.duration precomputados en AlgorithmMapper
                LocalDateTime actualDeparture = nextDeparture(flight.departureLocalTime, minDeparture);
                LocalDateTime actualArrival   = actualDeparture.plus(flight.duration);

                // Poda de horizonte
                long dayOffset = actualArrival.toLocalDate().toEpochDay() - readyEpoch;
                if (dayOffset < 0 || dayOffset >= DAY_SLOTS) continue;
                long usedMinutes = Duration.between(readyTime, actualArrival).toMinutes();
                if (usedMinutes > MAX_HORIZON_MIN) continue;

                // Restricción 1: capacidad del vuelo
                String flightKey = flight.id + "_" + actualDeparture.toLocalDate();
                if (getRemainingFlightCapacity(flight, flightKey) < batch.getQuantity()) continue;

                // Restricción 2: capacidad de almacén del aeropuerto de escala intermedia.
                // No aplica al destino final: esas maletas salen del sistema.
                String nextNode = flight.to.code;
                if (!nextNode.equals(targetNode)) {
                    Node nextAirport = graph.nodes.get(nextNode);
                    if (nextAirport != null && nextAirport.capacity > 0) {
                        String airportKey = nextNode + "_" + actualArrival.toLocalDate();
                        int ocupado = airportOccupancy.getOrDefault(airportKey, 0);
                        if (ocupado + batch.getQuantity() > nextAirport.capacity) continue;
                    }
                }

                // Actualizar mejor llegada por (nodo, día) usando array en vez de HashMap
                Integer nextIdx = nodeIndex.get(nextNode);
                if (nextIdx == null) continue;
                int cell = nextIdx * DAY_SLOTS + (int)dayOffset;
                if (bestTimes[cell] == null || actualArrival.isBefore(bestTimes[cell])) {
                    bestTimes[cell] = actualArrival;
                    pq.add(new RouteState(nextNode, actualArrival, actualDeparture, flight, current));
                }
            }
        }

        return RouteResult.EMPTY;
    }

    // Dado el LocalTime de salida y el instante más temprano permitido,
    // devuelve la próxima ocurrencia del vuelo: hoy si no pasó aún, mañana si ya pasó.
    private LocalDateTime nextDeparture(LocalTime flightTime, LocalDateTime earliest) {
        LocalDateTime candidate = LocalDateTime.of(earliest.toLocalDate(), flightTime);
        return candidate.isBefore(earliest) ? candidate.plusDays(1) : candidate;
    }

    private int getRemainingFlightCapacity(Edge flight, String flightKey) {
        return (int) flight.capacity - flightOccupancy.getOrDefault(flightKey, 0);
    }

    public void logEstadisticasCapacidad() {
        // Construir lookup: flightId → capacidad máxima
        Map<String, Integer> capacidadPorFlight = new HashMap<>();
        for (String code : graph.nodes.keySet()) {
            for (Edge e : graph.getNeighbors(code)) {
                capacidadPorFlight.put(e.id, (int) e.capacity);
            }
        }

        long flightDaysUsados  = flightOccupancy.size();
        long flightDaysLlenos  = 0;
        long flightDaysSobre   = 0;
        long totalAsignado     = 0;
        long totalCapacidad    = 0;
        List<String> sobre     = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : flightOccupancy.entrySet()) {
            // La clave es "flightId_yyyy-MM-dd"; el separador es el último '_'
            String key      = entry.getKey();
            int sep         = key.lastIndexOf('_');
            String flightId = sep > 0 ? key.substring(0, sep) : key;
            int asignado    = entry.getValue();
            totalAsignado  += asignado;

            Integer cap = capacidadPorFlight.get(flightId);
            if (cap != null) {
                totalCapacidad += cap;
                if (asignado >= cap) flightDaysLlenos++;
                if (asignado > cap)  { flightDaysSobre++; sobre.add(key + "=" + asignado + "/" + cap); }
            }
        }

        log.info("--- Capacidad de vuelos ---");
        log.info("  Flight-days con ocupación   : {}", flightDaysUsados);
        log.info("  Flight-days al 100 %         : {}", flightDaysLlenos);
        log.info("  Flight-days sobre capacidad  : {}", flightDaysSobre);
        if (totalCapacidad > 0) {
            log.info("  Utilización global           : {}/{} ({} %)",
                    totalAsignado, totalCapacidad, totalAsignado * 100 / totalCapacidad);
        }
        if (!sobre.isEmpty()) {
            log.warn("  Ejemplos sobre capacidad (race condition en paralelo):");
            sobre.stream().limit(5).forEach(s -> log.warn("    {}", s));
        }

        // --- Capacidad de aeropuertos ---
        log.info("--- Capacidad de aeropuertos (almacén) ---");
        long airportDaysUsados = airportOccupancy.size();
        long airportDaysLlenos = 0;
        long airportDaysSobre  = 0;
        long totalAirportAsig  = 0;
        long totalAirportCap   = 0;
        Map<String, long[]> porAero = new HashMap<>(); // [asignado, capacidad]

        for (Map.Entry<String, Integer> entry : airportOccupancy.entrySet()) {
            String key    = entry.getKey();
            int sep       = key.lastIndexOf('_');
            String code   = sep > 0 ? key.substring(0, sep) : key;
            int asignado  = entry.getValue();
            totalAirportAsig += asignado;

            Node nodo = graph.nodes.get(code);
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
        if (totalAirportCap > 0) {
            log.info("  Utilización global aerop.    : {}/{} ({} %)",
                    totalAirportAsig, totalAirportCap, totalAirportAsig * 100 / totalAirportCap);
        }
        log.info("  Top aeropuertos por ocupación total:");
        porAero.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(10)
                .forEach(e -> {
                    long asig = e.getValue()[0];
                    long cap  = e.getValue()[1];
                    String util = cap > 0 ? " (cap/día=" + cap + ")" : "";
                    log.info("    {} | total_transit={}{}", e.getKey(), asig, util);
                });
    }

    // Parent-pointer: cada estado solo almacena el puntero al padre, la arista
    // tomada y la hora de salida. La ruta completa se reconstruye al llegar al
    // destino en O(hops). Antes se copiaba la lista entera en cada transición → O(hops²).
    private static class RouteState {
        final String nodeCode;
        final LocalDateTime currentTime; // hora de llegada a este nodo
        final LocalDateTime departure;   // hora de salida del vuelo que llegó aquí (null en origen)
        final Edge edge;                 // vuelo tomado (null en origen)
        final RouteState parent;         // estado anterior (null en origen)

        RouteState(String nodeCode, LocalDateTime currentTime,
                   LocalDateTime departure, Edge edge, RouteState parent) {
            this.nodeCode    = nodeCode;
            this.currentTime = currentTime;
            this.departure   = departure;
            this.edge        = edge;
            this.parent      = parent;
        }
    }

    private static class RouteResult {
        final List<Edge> edges;
        final List<LocalDateTime> actualDepartures;
        final boolean cumpleSLA;

        static final RouteResult EMPTY = new RouteResult(Collections.emptyList(), Collections.emptyList(), false);

        RouteResult(List<Edge> edges, List<LocalDateTime> actualDepartures, boolean cumpleSLA) {
            this.edges            = edges;
            this.actualDepartures = actualDepartures;
            this.cumpleSLA        = cumpleSLA;
        }
    }
}
