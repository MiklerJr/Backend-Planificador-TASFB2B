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
    private static final int  MAX_CANDIDATE_LEGS = 10;
    private static final long SKELETON_BUCKET_MIN = 60L;   // bucket de hora-del-día para la cache cross-bloque
    private static final int  MAX_SKELETONS_POR_CLAVE = 6;

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

    // H3: cache de esqueletos de ruta (secuencias de edge-idx) reutilizable ENTRE
    // bloques de una misma simulación. La malla de vuelos se repite a diario, así que
    // un esqueleto hallado para (origen,destino,hora-del-día,SLA) sirve los 200 días;
    // solo se revalida capacidad/cancelaciones al materializar. Evita re-ejecutar
    // Dijkstra para patrones recurrentes. Instancia por simulación (GreedyRepairOperator
    // se crea una vez por escenario) y uso single-thread → HashMap normal es seguro.
    private final Map<Long, List<int[]>> rutaSkeletonCache = new HashMap<>();

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

    /**
     * Libera la capacidad ocupada por un batch en los mapas <b>globales</b>
     * (no del bloque actual). Usado cuando el {@link BacklogManager} reintenta
     * la ruta de un batch ya commiteado en bloques anteriores.
     *
     * <p>El llamador es responsable de invocar {@link LuggageBatch#clearRoute()}
     * después si va a reasignar inmediatamente.
     */
    public void releaseFromGlobal(LuggageBatch batch) {
        List<Edge> route = batch.getAssignedRoute();
        List<Long> deps  = batch.getAssignedDepartures();
        if (route == null || route.isEmpty() || deps == null || deps.isEmpty()) return;

        for (int i = 0; i < route.size(); i++) {
            Edge e      = route.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.durationMinutes;

            flightOccupancy.merge(flightKey(e.idx, depMin), -batch.getQuantity(), Integer::sum);

            boolean esFinalLeg = (i == route.size() - 1);
            if (!esFinalLeg && e.to.idx >= 0) {
                long arrDay     = arrMin / DAY_MIN;
                long nextDepMin = deps.get(i + 1);
                long depDay     = nextDepMin / DAY_MIN;
                for (long day = arrDay; day <= depDay; day++) {
                    airportOccupancy.merge(airportKey(e.to.idx, day * DAY_MIN),
                            -batch.getQuantity(), Integer::sum);
                }
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                airportOccupancy.merge(airportKey(e.to.idx, arrMin),
                        -batch.getQuantity(), Integer::sum);
            }
        }
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

    /**
     * Dijkstra hijo: genera rutas factibles para que un orquestador externo
     * (ACO padre) decida que asignacion conviene confirmar en el bloque.
     *
     * <p>El front no consume esta jerarquia; solo recibe el resultado final en
     * {@code SimulacionResponse}. Este metodo mantiene el mismo modelo temporal
     * y de capacidad que {@link #repair}: vuelos cancelados, capacidad global +
     * bloque, almacen, conexion minima, destino final y horizonte de 3 dias.
     */
    public List<RouteCandidate> generarCandidatosRuta(LuggageBatch batch,
                                                       Map<Long, Integer> blockFlight,
                                                       Map<Long, Integer> blockAirport,
                                                       int maxCandidatos) {
        if (batch == null || maxCandidatos <= 0) return Collections.emptyList();

        Node startNodeObj = graph.nodes.get(batch.getOriginCode());
        Node targetNodeObj = graph.nodes.get(batch.getDestCode());
        if (startNodeObj == null || targetNodeObj == null) return Collections.emptyList();

        int startIdx = startNodeObj.idx;
        int targetIdx = targetNodeObj.idx;
        if (startIdx < 0 || targetIdx < 0) return Collections.emptyList();

        long readyMin = toEpochMin(batch.getReadyTime());
        long readyDay = readyMin / DAY_MIN;
        long slaMaxMinutes = (long) batch.getSlaLimitHours() * 60L;

        // H3: fast-path por cache de esqueletos cross-bloque. Si ya conocemos rutas
        // para esta (origen,destino,hora-del-día,SLA), las materializamos contra la
        // capacidad vigente y, si alcanzan y la mejor cumple SLA, evitamos el Dijkstra.
        long skKey = skeletonKey(startIdx, targetIdx, readyMin, batch.getSlaLimitHours());
        List<int[]> cachedSk = rutaSkeletonCache.get(skKey);
        if (cachedSk != null && !cachedSk.isEmpty()) {
            List<RouteCandidate> reuso = new ArrayList<>(cachedSk.size());
            Set<String> firmasReuso = new HashSet<>();
            for (int[] sk : cachedSk) {
                RouteCandidate c = materializarSkeleton(batch, sk, blockFlight, blockAirport);
                if (c == null) continue;
                if (firmasReuso.add(c.signature())) reuso.add(c);
            }
            if (reuso.size() >= maxCandidatos) {
                reuso.sort(GreedyRepairOperator::compareRouteCandidates);
                // Solo confiamos en la cache si la mejor sigue siendo on-time; si la
                // capacidad las volvió tardías, recomputamos (puede haber otra ruta a tiempo).
                // NOTA: se probó un gate por congestión (K1, recompute si pressure>=0.80) y
                // REGRESIONÓ (disparó el cómputo en lanes cargados → corte de Ta → 520k sinRuta).
                // Revertido: el cuello no es congestión sino throughput/Ta (red al 1% de vuelos).
                if (reuso.get(0).cumpleSLA) {
                    return reuso.size() <= maxCandidatos ? reuso
                            : new ArrayList<>(reuso.subList(0, maxCandidatos));
                }
            }
        }

        int labelsPorCelda = Math.max(1, Math.min(4, maxCandidatos + 1));
        @SuppressWarnings("unchecked")
        List<RouteLabel>[] labels = new List[nodeCount * DAY_SLOTS];

        PriorityQueue<RouteState> pq = new PriorityQueue<>(
                Comparator.comparingLong((RouteState s) -> s.arrivalMin)
                        .thenComparingInt(s -> s.legs));

        long horizonDays = MAX_HORIZON_MIN / DAY_MIN;
        for (long d = 0; d <= horizonDays; d++) {
            long startMin = readyMin + d * DAY_MIN;
            int cell = startIdx * DAY_SLOTS + (int) d;
            RouteLabel label = new RouteLabel(startMin, 0, 0.0);
            addLabel(labels, cell, label, labelsPorCelda);
            pq.add(new RouteState(startIdx, startMin, -1L, null, null, 0, 0.0));
        }

        int limiteObjetivo = maxCandidatos <= 1 ? 1 : Math.max(maxCandidatos, maxCandidatos * 2);
        int maxExpansiones = Math.max(256, maxCandidatos * Math.max(1, nodeCount) * DAY_SLOTS * 8);
        int expansiones = 0;
        int candidatosOnTime = 0;
        List<RouteCandidate> candidatos = new ArrayList<>(limiteObjetivo);
        Set<String> firmas = new HashSet<>();

        while (!pq.isEmpty() && candidatos.size() < limiteObjetivo && expansiones++ < maxExpansiones) {
            RouteState current = pq.poll();

            if (current.nodeIdx == targetIdx && current.edge != null) {
                RouteCandidate candidate = toRouteCandidate(current, batch, readyMin, slaMaxMinutes,
                        blockFlight, blockAirport);
                if (candidate != null && firmas.add(candidate.signature())) {
                    if (candidate.cumpleSLA) {
                        candidatosOnTime++;
                    } else if (candidatosOnTime >= maxCandidatos) {
                        continue;
                    }
                    candidatos.add(candidate);
                }
                continue;
            }

            if (current.legs >= MAX_CANDIDATE_LEGS) continue;

            for (Edge flight : adjByIdx[current.nodeIdx]) {
                if (flight.to == null || flight.to.idx < 0) continue;
                int nextIdx = flight.to.idx;
                if (containsNode(current, nextIdx)) continue;

                long minWait = (current.edge == null) ? 0L : CONNECTION_MIN;
                long earliest = current.arrivalMin + minWait;
                long actualDep = nextDepartureMin(flight.depMinuteOfDay, earliest);
                long actualArr = actualDep + flight.durationMinutes;

                long dayOffset = actualArr / DAY_MIN - readyDay;
                if (dayOffset < 0 || dayOffset >= DAY_SLOTS) continue;
                if (actualArr - readyMin > MAX_HORIZON_MIN) continue;
                if (candidatosOnTime >= maxCandidatos
                        && (actualArr + DEST_STORAGE_MIN) - readyMin > slaMaxMinutes) {
                    continue;
                }
                if (remainingFlight(flight, actualDep, blockFlight) < batch.getQuantity()) continue;
                if (!hasAirportCapacity(flight.to, nextIdx == targetIdx, actualArr, batch.getQuantity(), blockAirport)) {
                    continue;
                }

                int cell = nextIdx * DAY_SLOTS + (int) dayOffset;
                double pressure = Math.max(current.pressure, projectedStepPressure(
                        flight, actualDep, nextIdx == targetIdx, batch.getQuantity(), blockFlight, blockAirport));
                RouteLabel label = new RouteLabel(actualArr, current.legs + 1, pressure);
                if (isDominated(labels[cell], label)) continue;
                addLabel(labels, cell, label, labelsPorCelda);
                pq.add(new RouteState(nextIdx, actualArr, actualDep, flight, current,
                        current.legs + 1, pressure));
            }
        }

        candidatos.sort(GreedyRepairOperator::compareRouteCandidates);

        // H3: guardar los esqueletos hallados para reusarlos en bloques futuros.
        if (!candidatos.isEmpty()) {
            List<int[]> sks = new ArrayList<>(Math.min(candidatos.size(), MAX_SKELETONS_POR_CLAVE));
            for (RouteCandidate c : candidatos) {
                if (sks.size() >= MAX_SKELETONS_POR_CLAVE) break;
                List<Edge> es = c.getEdges();
                int[] arr = new int[es.size()];
                for (int i = 0; i < es.size(); i++) arr[i] = es.get(i).idx;
                sks.add(arr);
            }
            rutaSkeletonCache.put(skKey, sks);
        }

        if (candidatos.size() <= maxCandidatos) return candidatos;
        return new ArrayList<>(candidatos.subList(0, maxCandidatos));
    }

    /** Clave de la cache de esqueletos: origen, destino, hora-del-día y SLA (independiente del día). */
    private static long skeletonKey(int startIdx, int targetIdx, long readyMin, int slaHours) {
        long hourBucket = (readyMin % DAY_MIN) / SKELETON_BUCKET_MIN;   // 0..23
        return ((long) startIdx << 40)
                | ((long) targetIdx << 24)
                | (hourBucket << 8)
                | (slaHours & 0xFFL);
    }

    /** Materializa un esqueleto (secuencia de edge-idx) contra la capacidad vigente; null si inviable. */
    private RouteCandidate materializarSkeleton(LuggageBatch batch,
                                                int[] edgeIdxs,
                                                Map<Long, Integer> blockFlight,
                                                Map<Long, Integer> blockAirport) {
        if (edgeIdxs == null || edgeIdxs.length == 0) return null;
        List<Edge> ruta = new ArrayList<>(edgeIdxs.length);
        for (int idx : edgeIdxs) {
            if (idx < 0 || idx >= edgeByIdx.length) return null;
            Edge e = edgeByIdx[idx];
            if (e == null) return null;
            ruta.add(e);
        }
        return materializarRutaCandidata(batch, ruta, blockFlight, blockAirport);
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

    // -----------------------------------------------------------------------
    // API pública para motores alternativos (ACO) — reutiliza el modelo
    // temporal y de ocupación del operador greedy para que las rutas sean
    // comparables con las del ALNS.
    // -----------------------------------------------------------------------

    /**
     * Calcula la próxima salida ≥ earliest del vuelo cuyo horario diario es {@code depMinuteOfDay}.
     */
    public long calcularProximaSalida(int depMinuteOfDay, long earliest) {
        return nextDepartureMin(depMinuteOfDay, earliest);
    }

    /**
     * Capacidad restante de un vuelo en un día dado (resta global + bloque y vuelos cancelados).
     */
    public int capacidadRestante(Edge flight, long depMin, Map<Long, Integer> blockFlight) {
        return remainingFlight(flight, depMin, blockFlight);
    }

    /**
     * Capacidad restante de almacén de un nodo en un día dado.
     */
    public int capacidadAlmacen(Node node, long arrMin, Map<Long, Integer> blockAirport) {
        if (node == null || node.idx < 0 || node.capacity <= 0) return Integer.MAX_VALUE;
        long key = airportKey(node.idx, arrMin);
        return node.capacity
             - airportOccupancy.getOrDefault(key, 0)
             - blockAirport.getOrDefault(key, 0);
    }

    /**
     * Aplica una asignación ya calculada (route + departures + cumpleSLA) al bloque.
     * Equivalente público a {@link #applyToBlock} para que un motor externo (p. ej. ACO)
     * actualice los mismos contadores que usa el ALNS.
     */
    public void aplicarAsignacionBloque(LuggageBatch batch,
                                         Map<Long, Integer> blockFlight,
                                         Map<Long, Integer> blockAirport) {
        List<Edge> route = batch.getAssignedRoute();
        List<Long> deps  = batch.getAssignedDepartures();
        if (route == null || route.isEmpty() || deps == null || deps.size() != route.size()) return;
        RouteResult fake = new RouteResult(route, deps, batch.isCumpleSLA());
        applyToBlock(batch, fake, blockFlight, blockAirport);
    }

    /** Conversión auxiliar usada por motores externos para alinearse con el modelo temporal. */
    public static long toEpochMinPublic(LocalDateTime dt) {
        return toEpochMin(dt);
    }

    /**
     * Fast path para motores externos (ACO): intenta resolver el batch con el
     * mismo Dijkstra earliest-arrival que usa el {@code repair()} del ALNS.
     * Si encuentra ruta, asigna {@code assignedRoute}, {@code assignedDepartures}
     * y {@code cumpleSLA} al batch y retorna {@code true}. NO aplica el bloque
     * — el caller debe invocar {@link #aplicarAsignacionBloque} cuando decida
     * confirmar la asignación.
     *
     * <p>Pensado para que {@code AcoBlockEngine} ejerza Dijkstra-first y reserve
     * la corrida completa de ACO para casos donde Dijkstra no logra ruta o no
     * cumple SLA. La earliest-arrival devuelta también es óptima en tiempo de
     * tránsito, por lo que cuando cumple SLA es la mejor ruta posible.
     *
     * @return {@code true} si se asignó ruta (mire {@code batch.isCumpleSLA()}
     * para saber si la ruta es on-time o tardía).
     */
    public boolean intentarDijkstraDirecto(LuggageBatch batch,
                                            Map<Long, Integer> blockFlight,
                                            Map<Long, Integer> blockAirport) {
        List<RouteCandidate> candidates = generarCandidatosRuta(batch, blockFlight, blockAirport, 1);
        if (candidates.isEmpty()) return false;
        aplicarCandidatoRuta(batch, candidates.get(0));
        return true;
    }

    /**
     * Recalcula tiempos reales y revalida capacidad de un esqueleto de ruta
     * cacheado contra el estado actual del bloque. Si algun vuelo/almacen ya no
     * tiene capacidad, devuelve {@code null}; el ACO padre debe caer otra vez al
     * Dijkstra hijo para buscar alternativas.
     */
    public RouteCandidate materializarRutaCandidata(LuggageBatch batch,
                                                    List<Edge> ruta,
                                                    Map<Long, Integer> blockFlight,
                                                    Map<Long, Integer> blockAirport) {
        if (batch == null || ruta == null || ruta.isEmpty()) return null;

        long readyMin = toEpochMin(batch.getReadyTime());
        long slaMaxMinutes = (long) batch.getSlaLimitHours() * 60L;
        long earliest = readyMin;
        String expectedFrom = batch.getOriginCode();
        List<Edge> edges = new ArrayList<>(ruta.size());
        List<Long> deps = new ArrayList<>(ruta.size());

        for (int i = 0; i < ruta.size(); i++) {
            Edge edge = ruta.get(i);
            if (edge == null || edge.from == null || edge.to == null) return null;
            if (!Objects.equals(expectedFrom, edge.from.code)) return null;

            boolean finalLeg = i == ruta.size() - 1;
            if (finalLeg && !Objects.equals(batch.getDestCode(), edge.to.code)) return null;

            long minDeparture = (i == 0) ? earliest : earliest + CONNECTION_MIN;
            long depMin = nextDepartureMin(edge.depMinuteOfDay, minDeparture);
            long arrMin = depMin + edge.durationMinutes;
            boolean found = false;
            while (arrMin - readyMin <= MAX_HORIZON_MIN) {
                if (remainingFlight(edge, depMin, blockFlight) >= batch.getQuantity()
                        && hasAirportCapacity(edge.to, finalLeg, arrMin, batch.getQuantity(), blockAirport)) {
                    found = true;
                    break;
                }
                depMin += DAY_MIN;
                arrMin = depMin + edge.durationMinutes;
            }
            if (!found) return null;

            edges.add(edge);
            deps.add(depMin);
            earliest = arrMin;
            expectedFrom = edge.to.code;
        }

        return toRouteCandidate(edges, deps, batch, readyMin, slaMaxMinutes, blockFlight, blockAirport);
    }

    /**
     * Materializa una ruta candidata sobre el batch sin aplicar capacidad al
     * bloque. El ACO padre llama luego a {@link #aplicarAsignacionBloque} solo
     * cuando decide confirmar esa asignacion.
     */
    public void aplicarCandidatoRuta(LuggageBatch batch, RouteCandidate candidate) {
        if (batch == null || candidate == null) return;
        batch.setAssignedRoute(new ArrayList<>(candidate.edges));
        batch.setAssignedDepartures(new ArrayList<>(candidate.actualDepartures));
        batch.setCumpleSLA(candidate.cumpleSLA);
    }

    /**
     * Aplica una ruta candidata al bloque sin mutar el batch. Esto permite que
     * las hormigas del ACO simulen capacidad localmente y que solo la mejor
     * solucion se materialice en los batches al final.
     */
    public void aplicarCandidatoBloque(LuggageBatch batch,
                                       RouteCandidate candidate,
                                       Map<Long, Integer> blockFlight,
                                       Map<Long, Integer> blockAirport) {
        if (batch == null || candidate == null) return;
        RouteResult fake = new RouteResult(candidate.edges, candidate.actualDepartures, candidate.cumpleSLA);
        applyToBlock(batch, fake, blockFlight, blockAirport);
    }

    /**
     * Fase I — ¿una ruta YA materializada (para otro envío del mismo grupo) puede
     * servir tal cual a {@code batch}? Reutiliza las mismas aristas y salidas reales
     * sin reconstruir; solo verifica, barato, lo que depende del envío concreto:
     * <ul>
     *   <li><b>temporal</b>: el primer vuelo no sale antes de que el envío esté listo
     *       ({@code dep0 >= readyMin}); las conexiones intermedias ya son válidas;</li>
     *   <li><b>SLA on-time</b> recalculado contra el {@code readyTime} de ESTE envío;</li>
     *   <li><b>capacidad</b> (vuelo + almacén, incl. overnight de escala y vuelos
     *       cancelados) para SU cantidad, contra el estado vigente del bloque.</li>
     * </ul>
     * Permite resolver un grupo (mismo origen/destino/hora/SLA) con una sola
     * materialización y repartir la demanda por capacidad. Devuelve {@code false} si
     * no sirve (el caller recalcula específicamente para el envío).
     */
    public boolean rutaSirveParaBatch(RouteCandidate candidate,
                                      LuggageBatch batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport) {
        return rutaSirveParaBatch(candidate, batch, blockFlight, blockAirport, 0.0);
    }

    /**
     * Variante con <b>reserva (J4)</b>: para un envío flexible exige que cada vuelo
     * conserve un colchón de capacidad libre (`reservaBase · capacidad`, escalado por la
     * holgura del envío), protegiendo los vuelos cuello-de-botella para los envíos de SLA
     * corto. Con `reservaBase = 0` equivale al chequeo normal. El caller debe reintentar
     * con `reservaBase = 0` si ninguna ruta pasa, para no crear un sinRuta evitable.
     */
    public boolean rutaSirveParaBatch(RouteCandidate candidate,
                                      LuggageBatch batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport,
                                      double reservaBase) {
        if (candidate == null || batch == null) return false;
        List<Edge> edges = candidate.getEdges();
        List<Long> deps = candidate.getActualDepartures();
        if (edges.isEmpty() || deps.size() != edges.size()) return false;

        long readyMin = toEpochMin(batch.getReadyTime());
        if (deps.get(0) < readyMin) return false;   // el envío aún no estaba listo

        long arrMin = deps.get(deps.size() - 1) + edges.get(edges.size() - 1).durationMinutes;
        long transitMin = (arrMin + DEST_STORAGE_MIN) - readyMin;
        if (transitMin > (long) batch.getSlaLimitHours() * 60L) return false;   // tardaría

        // Colchón de reserva proporcional a la holgura (urgente ⇒ 0 ⇒ sin reserva).
        double slackRatio = 0.0;
        if (reservaBase > 0.0) {
            double slaMin = Math.max(1.0, batch.getSlaLimitHours() * 60.0);
            slackRatio = Math.max(0.0, Math.min(1.0, candidate.getSlackMin() / slaMin));
        }
        double reserva = reservaBase * slackRatio;

        int qty = batch.getQuantity();
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            long depMin = deps.get(i);
            int colchon = reserva > 0.0 && e.capacity > 0 ? (int) Math.ceil(reserva * e.capacity) : 0;
            if (remainingFlight(e, depMin, blockFlight) < qty + colchon) return false;
            boolean finalLeg = (i == edges.size() - 1);
            if (!hasAirportCapacity(e.to, finalLeg, depMin + e.durationMinutes, qty, blockAirport)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Devuelve el conjunto de claves (flight-day y airport-day) que una ruta
     * candidata ocupa en el bloque. Reproduce exactamente el mismo recorrido que
     * {@link #applyToBlock} (incluida la estadía overnight de escalas y la regla
     * de destino final), de modo que un motor externo (ACO) pueda detectar si una
     * asignación recién confirmada toca alguno de estos vuelos/almacenes sin
     * duplicar la regla de capacidad.
     */
    public Set<Long> clavesOcupadas(RouteCandidate candidate) {
        if (candidate == null) return Collections.emptySet();
        List<Edge> edges = candidate.getEdges();
        List<Long> deps = candidate.getActualDepartures();
        if (edges.isEmpty() || deps.size() != edges.size()) return Collections.emptySet();

        Set<Long> keys = new HashSet<>(edges.size() * 3);
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.durationMinutes;

            keys.add(flightKey(e.idx, depMin));

            boolean esFinalLeg = (i == edges.size() - 1);
            if (!esFinalLeg && e.to.idx >= 0) {
                long arrDay = arrMin / DAY_MIN;
                long depDay = deps.get(i + 1) / DAY_MIN;
                for (long day = arrDay; day <= depDay; day++) {
                    keys.add(airportKey(e.to.idx, day * DAY_MIN));
                }
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                keys.add(airportKey(e.to.idx, arrMin));
            }
        }
        return keys;
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

    private boolean hasAirportCapacity(Node node,
                                       boolean destinoFinal,
                                       long arrMin,
                                       int qty,
                                       Map<Long, Integer> blockAirport) {
        if (node == null || node.idx < 0 || node.capacity <= 0) return true;
        long ak = airportKey(node.idx, arrMin);
        if (airportOccupancy.getOrDefault(ak, 0) + blockAirport.getOrDefault(ak, 0) + qty > node.capacity) {
            return false;
        }
        if (!destinoFinal) {
            long akD1 = airportKey(node.idx, arrMin + DAY_MIN);
            return airportOccupancy.getOrDefault(akD1, 0) + blockAirport.getOrDefault(akD1, 0) + qty <= node.capacity;
        }
        return true;
    }

    private RouteCandidate toRouteCandidate(RouteState state,
                                            LuggageBatch batch,
                                            long readyMin,
                                            long slaMaxMinutes,
                                            Map<Long, Integer> blockFlight,
                                            Map<Long, Integer> blockAirport) {
        List<Edge> edges = new ArrayList<>();
        List<Long> deps = new ArrayList<>();
        for (RouteState s = state; s.edge != null; s = s.parent) {
            edges.add(0, s.edge);
            deps.add(0, s.depMin);
        }
        return toRouteCandidate(edges, deps, batch, readyMin, slaMaxMinutes, blockFlight, blockAirport);
    }

    private RouteCandidate toRouteCandidate(List<Edge> edges,
                                            List<Long> deps,
                                            LuggageBatch batch,
                                            long readyMin,
                                            long slaMaxMinutes,
                                            Map<Long, Integer> blockFlight,
                                            Map<Long, Integer> blockAirport) {
        if (edges.isEmpty() || deps.size() != edges.size()) return null;

        long arrivalMin = deps.get(deps.size() - 1) + edges.get(edges.size() - 1).durationMinutes;
        long transitMin = (arrivalMin + DEST_STORAGE_MIN) - readyMin;
        long slackMin = slaMaxMinutes - transitMin;
        double pressure = projectedPressure(edges, deps, batch, blockFlight, blockAirport);
        double scarcity = projectedScarcity(edges, deps, blockFlight, blockAirport);
        return new RouteCandidate(edges, deps, transitMin <= slaMaxMinutes,
                arrivalMin, transitMin, slackMin, pressure, scarcity);
    }

    /**
     * Fase J — precio de congestión de un recurso (vuelo-día o almacén-día) según su
     * utilización ACTUAL {@code u = usado/capacidad}. Convexo: ~0 mientras hay holgura
     * y se dispara al acercarse a la saturación, de modo que las rutas que pasan por
     * recursos escasos cuestan mucho y solo se eligen cuando no hay alternativa o el
     * envío es urgente.
     */
    static double precioCongestion(int usado, int capacidad) {
        if (capacidad <= 0) return 0.0;
        double u = (double) usado / capacidad;
        if (u <= 0.0) return 0.0;
        if (u >= 1.0) return 1000.0;
        return (u * u * u) / Math.max(0.02, 1.0 - u);   // ≈0 hasta ~0.6, explota cerca de 1
    }

    /** Suma del precio de congestión a lo largo de los vuelos y estadías en almacén de la ruta. */
    private double projectedScarcity(List<Edge> edges,
                                     List<Long> deps,
                                     Map<Long, Integer> blockFlight,
                                     Map<Long, Integer> blockAirport) {
        double sum = 0.0;
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            long depMin = deps.get(i);
            if (e.capacity > 0) {
                int remaining = remainingFlight(e, depMin, blockFlight);
                sum += precioCongestion(e.capacity - remaining, e.capacity);
            }
            if (e.to != null && e.to.idx >= 0 && e.to.capacity > 0) {
                long arrMin = depMin + e.durationMinutes;
                int remaining = capacidadAlmacen(e.to, arrMin, blockAirport);
                sum += precioCongestion(e.to.capacity - remaining, e.to.capacity);
                if (i < edges.size() - 1) {
                    int remNext = capacidadAlmacen(e.to, arrMin + DAY_MIN, blockAirport);
                    sum += precioCongestion(e.to.capacity - remNext, e.to.capacity);
                }
            }
        }
        return sum;
    }

    private double projectedStepPressure(Edge edge,
                                         long depMin,
                                         boolean destinoFinal,
                                         int qty,
                                         Map<Long, Integer> blockFlight,
                                         Map<Long, Integer> blockAirport) {
        double max = 0.0;
        if (edge.capacity > 0) {
            int remaining = remainingFlight(edge, depMin, blockFlight);
            max = Math.max(max, (double) (edge.capacity - remaining + qty) / edge.capacity);
        }
        if (edge.to != null && edge.to.idx >= 0 && edge.to.capacity > 0) {
            long arrMin = depMin + edge.durationMinutes;
            int remaining = capacidadAlmacen(edge.to, arrMin, blockAirport);
            max = Math.max(max, (double) (edge.to.capacity - remaining + qty) / edge.to.capacity);
            if (!destinoFinal) {
                int remainingNextDay = capacidadAlmacen(edge.to, arrMin + DAY_MIN, blockAirport);
                max = Math.max(max, (double) (edge.to.capacity - remainingNextDay + qty) / edge.to.capacity);
            }
        }
        return max;
    }

    private double projectedPressure(List<Edge> edges,
                                     List<Long> deps,
                                     LuggageBatch batch,
                                     Map<Long, Integer> blockFlight,
                                     Map<Long, Integer> blockAirport) {
        double max = 0.0;
        int qty = batch.getQuantity();
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            long depMin = deps.get(i);
            if (e.capacity > 0) {
                int remaining = remainingFlight(e, depMin, blockFlight);
                max = Math.max(max, (double) (e.capacity - remaining + qty) / e.capacity);
            }

            if (e.to != null && e.to.idx >= 0 && e.to.capacity > 0) {
                long arrMin = depMin + e.durationMinutes;
                int remaining = capacidadAlmacen(e.to, arrMin, blockAirport);
                max = Math.max(max, (double) (e.to.capacity - remaining + qty) / e.to.capacity);
                if (i < edges.size() - 1) {
                    int remainingNextDay = capacidadAlmacen(e.to, arrMin + DAY_MIN, blockAirport);
                    max = Math.max(max, (double) (e.to.capacity - remainingNextDay + qty) / e.to.capacity);
                }
            }
        }
        return max;
    }

    private boolean containsNode(RouteState state, int nodeIdx) {
        for (RouteState s = state; s != null; s = s.parent) {
            if (s.nodeIdx == nodeIdx) return true;
        }
        return false;
    }

    private static boolean isDominated(List<RouteLabel> labels, RouteLabel candidate) {
        if (labels == null || labels.isEmpty()) return false;
        for (RouteLabel label : labels) {
            if (label.arrivalMin <= candidate.arrivalMin
                    && label.legs <= candidate.legs
                    && label.pressure <= candidate.pressure) {
                return true;
            }
        }
        return false;
    }

    private static void addLabel(List<RouteLabel>[] labels,
                                 int cell,
                                 RouteLabel candidate,
                                 int maxLabels) {
        List<RouteLabel> bucket = labels[cell];
        if (bucket == null) {
            bucket = new ArrayList<>(maxLabels);
            labels[cell] = bucket;
        }
        bucket.removeIf(existing ->
                candidate.arrivalMin <= existing.arrivalMin
                        && candidate.legs <= existing.legs
                        && candidate.pressure <= existing.pressure);
        if (bucket.size() >= maxLabels) {
            int worstIdx = 0;
            for (int i = 1; i < bucket.size(); i++) {
                if (compareLabel(bucket.get(i), bucket.get(worstIdx)) > 0) {
                    worstIdx = i;
                }
            }
            RouteLabel worst = bucket.get(worstIdx);
            if (compareLabel(candidate, worst) >= 0) return;
            bucket.remove(worstIdx);
        }
        bucket.add(candidate);
    }

    private static int compareLabel(RouteLabel a, RouteLabel b) {
        int c = Long.compare(a.arrivalMin, b.arrivalMin);
        if (c != 0) return c;
        c = Integer.compare(a.legs, b.legs);
        if (c != 0) return c;
        return Double.compare(a.pressure, b.pressure);
    }

    private static int compareRouteCandidates(RouteCandidate a, RouteCandidate b) {
        int c = Boolean.compare(b.cumpleSLA, a.cumpleSLA);
        if (c != 0) return c;
        c = Long.compare(Math.max(0L, -a.slackMin), Math.max(0L, -b.slackMin));
        if (c != 0) return c;
        c = Long.compare(a.transitMin, b.transitMin);
        if (c != 0) return c;
        c = Double.compare(a.pressure, b.pressure);
        if (c != 0) return c;
        c = Integer.compare(a.edges.size(), b.edges.size());
        if (c != 0) return c;
        return Long.compare(b.slackMin, a.slackMin);
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
        final int        legs;
        final double     pressure;

        RouteState(int nodeIdx, long arrivalMin, long depMin, Edge edge, RouteState parent) {
            this(nodeIdx, arrivalMin, depMin, edge, parent, parent == null ? 0 : parent.legs + 1,
                    parent == null ? 0.0 : parent.pressure);
        }

        RouteState(int nodeIdx, long arrivalMin, long depMin, Edge edge, RouteState parent, int legs) {
            this(nodeIdx, arrivalMin, depMin, edge, parent, legs, parent == null ? 0.0 : parent.pressure);
        }

        RouteState(int nodeIdx, long arrivalMin, long depMin, Edge edge, RouteState parent, int legs, double pressure) {
            this.nodeIdx    = nodeIdx;
            this.arrivalMin = arrivalMin;
            this.depMin     = depMin;
            this.edge       = edge;
            this.parent     = parent;
            this.legs       = legs;
            this.pressure   = pressure;
        }
    }

    private static final class RouteLabel {
        final long arrivalMin;
        final int legs;
        final double pressure;

        RouteLabel(long arrivalMin, int legs, double pressure) {
            this.arrivalMin = arrivalMin;
            this.legs = legs;
            this.pressure = pressure;
        }
    }

    /** Ruta factible generada por Dijkstra hijo para que ACO padre la asigne. */
    public static final class RouteCandidate {
        private final List<Edge> edges;
        private final List<Long> actualDepartures;
        private final boolean cumpleSLA;
        private final long arrivalMin;
        private final long transitMin;
        private final long slackMin;
        private final double pressure;
        private final double scarcityCost;
        private String signatureCache;

        private RouteCandidate(List<Edge> edges,
                               List<Long> actualDepartures,
                               boolean cumpleSLA,
                               long arrivalMin,
                               long transitMin,
                               long slackMin,
                               double pressure,
                               double scarcityCost) {
            this.edges = List.copyOf(edges);
            this.actualDepartures = List.copyOf(actualDepartures);
            this.cumpleSLA = cumpleSLA;
            this.arrivalMin = arrivalMin;
            this.transitMin = transitMin;
            this.slackMin = slackMin;
            this.pressure = pressure;
            this.scarcityCost = scarcityCost;
        }

        public List<Edge> getEdges() { return edges; }
        public List<Long> getActualDepartures() { return actualDepartures; }
        public boolean isCumpleSLA() { return cumpleSLA; }
        public long getArrivalMin() { return arrivalMin; }
        public long getTransitMin() { return transitMin; }
        public long getSlackMin() { return slackMin; }
        public double getPressure() { return pressure; }
        /** Fase J: costo de congestión de la ruta = Σ precio(utilización) de sus vuelos y almacenes. */
        public double getScarcityCost() { return scarcityCost; }
        public int getLegs() { return edges.size(); }

        /**
         * Firma estable de la ruta ({@code edgeIdx@dep;...}). Cacheada porque
         * motores externos (ACO) la usan como clave de feromona/deduplicación en
         * el bucle caliente.
         */
        public String signature() {
            String cached = signatureCache;
            if (cached != null) return cached;
            StringBuilder sb = new StringBuilder(edges.size() * 12);
            for (int i = 0; i < edges.size(); i++) {
                sb.append(edges.get(i).idx).append('@').append(actualDepartures.get(i)).append(';');
            }
            cached = sb.toString();
            signatureCache = cached;
            return cached;
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
