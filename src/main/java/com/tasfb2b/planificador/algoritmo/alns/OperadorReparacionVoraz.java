package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

@Slf4j
public class OperadorReparacionVoraz implements OperadorReparacion {

    private static final long CONNECTION_MIN   = 10L;
    private static final long DEST_STORAGE_MIN = 10L;
    public static final long STORAGE_SLOT_MIN = 60L;
    private static final long MAX_HORIZON_MIN  = 3 * 24 * 60L;
    private static final long DAY_MIN          = CodificadorClaveVuelo.DAY_MIN;
    private static final int  DAY_BITS         = CodificadorClaveVuelo.DAY_BITS;
    private static final int  MAX_CANDIDATE_LEGS = 10;
    private static final long SKELETON_BUCKET_MIN = 60L;   // bucket de hora-del-dÃ­a para la cache cross-bloque
    private static final int  MAX_SKELETONS_POR_CLAVE = 8;   // sitio para esqueletos hub-avoiding
    private static final int    HUB_RECLASIFICAR_CADA = 10;   // bloques entre reclasificaciones
    private double umbralHubPico      = 0.65;   // fracciÃ³n de cap a la que un nodo pasa a hub
    private double precioHubExponente = 2.0;    // exponente p de u^p/(1âˆ’u) en el precio de hub
    private boolean[] hubByIdx;          // consulta O(1) en el bucle caliente; arranca vacÃ­o
    private int commitsDesdeReclasificar = 0;

    private final Grafo graph;

    private final int      nodeCount;
    private static final int DAY_SLOTS = (int)(MAX_HORIZON_MIN / DAY_MIN) + 1; // 4

    private final Arista[]       edgeByIdx;
    private final String[]     nodeByIdx;
    private final List<Arista>[] adjByIdx;   // adjByIdx[node.idx] â†’ vecinos salientes

    private final ConcurrentHashMap<Long, Integer> flightOccupancy  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> airportOccupancy = new ConcurrentHashMap<>();

    private final Set<Long> cancelledFlightDays = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<Long, Integer> backlogOrigenOcc = new ConcurrentHashMap<>();
    private long relojUtcMin = Long.MIN_VALUE;
    private final Set<String> origenAdmitidos = new HashSet<>();

    final Map<Long, List<int[]>> rutaSkeletonCache;   // package-private para tests; se asigna en el constructor
    private final Set<Long> reSeeded = new HashSet<>();

    public OperadorReparacionVoraz(Grafo graph) {
        this(graph, new HashMap<>());
    }

    public OperadorReparacionVoraz(Grafo graph, Map<Long, List<int[]>> rutaSkeletonCache) {
        this.rutaSkeletonCache = rutaSkeletonCache;
        this.graph = graph;

        // Asignar idx entero a cada nodo
        Map<String, Integer> nodeIndex = new HashMap<>(graph.nodes.size() * 2);
        int i = 0;
        for (Map.Entry<String, Nodo> entry : graph.nodes.entrySet()) {
            nodeIndex.put(entry.getKey(), i);
            entry.getValue().idx = i;
            i++;
        }
        nodeCount = i;

        nodeByIdx = new String[nodeCount];
        for (Map.Entry<String, Integer> e : nodeIndex.entrySet()) nodeByIdx[e.getValue()] = e.getKey();

        int maxIdx = -1;
        for (Arista e : graph.edges) if (e.idx > maxIdx) maxIdx = e.idx;
        edgeByIdx = new Arista[maxIdx + 1];
        for (Arista e : graph.edges) edgeByIdx[e.idx] = e;

        // Lista de adyacencia indexada por node.idx (evita HashMap lookup en inner loop)
        @SuppressWarnings("unchecked")
        List<Arista>[] adj = new List[nodeCount];
        for (int j = 0; j < nodeCount; j++) adj[j] = new ArrayList<>();
        for (Arista e : graph.edges) {
            if (e.from != null && e.from.idx >= 0) adj[e.from.idx].add(e);
        }
        adjByIdx = adj;

        this.hubByIdx = new boolean[nodeCount];
    }

    public void setHubs(Set<String> codigos) {
        marcarHubs(codigos == null ? Collections.emptySet() : codigos);
    }

    public void configurarStorageAware(double umbralHubPico, double precioHubExponente) {
        if (umbralHubPico > 0.0) this.umbralHubPico = umbralHubPico;
        if (precioHubExponente > 0.0) this.precioHubExponente = precioHubExponente;
    }

    private void marcarHubs(Set<String> codigos) {
        boolean[] flags = new boolean[nodeCount];
        for (int idx = 0; idx < nodeCount; idx++) {
            flags[idx] = nodeByIdx[idx] != null && codigos.contains(nodeByIdx[idx]);
        }
        this.hubByIdx = flags;
    }

    private boolean esHub(int nodeIdx) {
        return nodeIdx >= 0 && nodeIdx < hubByIdx.length && hubByIdx[nodeIdx];
    }

    public void reclasificarHubsPorUtilizacion(double umbralPico) {
        double[] picoUtil = new double[nodeCount];
        for (Map.Entry<Long, Integer> entry : airportOccupancy.entrySet()) {
            int nodeIdx = (int) (entry.getKey() >> DAY_BITS);
            if (nodeIdx < 0 || nodeIdx >= nodeCount) continue;
            String code = nodeByIdx[nodeIdx];
            Nodo nodo = code != null ? graph.nodes.get(code) : null;
            if (nodo == null || nodo.capacity <= 0) continue;
            double util = entry.getValue() / (double) nodo.capacity;
            if (util > picoUtil[nodeIdx]) picoUtil[nodeIdx] = util;
        }

        boolean[] flags = new boolean[nodeCount];
        for (int idx = 0; idx < nodeCount; idx++) {
            flags[idx] = picoUtil[idx] >= umbralPico;
        }
        this.hubByIdx = flags;
    }

    public PreColapso evaluarPreColapso(Map<Long, Integer> blockAirport,
                                        Collection<LoteEnvio> pendientes) {
        double utilMax = 0.0;
        String almacenCritico = null;
        if (blockAirport != null) {
            for (Long key : blockAirport.keySet()) {
                int nodeIdx = (int) (key >> DAY_BITS);
                if (nodeIdx < 0 || nodeIdx >= nodeCount) continue;
                String code = nodeByIdx[nodeIdx];
                Nodo nodo = code != null ? graph.nodes.get(code) : null;
                if (nodo == null || nodo.capacity <= 0) continue;
                int ocupado = airportOccupancy.getOrDefault(key, 0)
                        + backlogOrigenOcc.getOrDefault(key, 0);
                double util = ocupado / (double) nodo.capacity;
                if (util > utilMax) { utilMax = util; almacenCritico = code; }
            }
        }

        double holguraMin = 1.0;
        String envioUrgente = null;
        if (pendientes != null && relojUtcMin != Long.MIN_VALUE) {
            for (LoteEnvio b : pendientes) {
                if (b == null || b.getReadyTime() == null || b.getSlaLimitHours() <= 0) continue;
                long slaMin = (long) b.getSlaLimitHours() * 60L;
                long restante = (toEpochMin(b.getReadyTime()) + slaMin) - relojUtcMin;
                double ratio = restante / (double) slaMin;       // <0 = ya vencido
                if (ratio < holguraMin) { holguraMin = ratio; envioUrgente = b.getId(); }
            }
        }
        return new PreColapso(utilMax, almacenCritico, holguraMin, envioUrgente);
    }

    public record PreColapso(double utilAlmacenMax, String almacenCritico,
                             double holguraSlaMin, String envioUrgente) {}

    private int[] esqueletoEvitandoHubs(int startIdx, int targetIdx, long readyMin, int slaHours) {
        if (startIdx < 0 || targetIdx < 0 || startIdx == targetIdx) return null;
        long readyDay = readyMin / DAY_MIN;
        long slaMaxMinutes = (long) slaHours * 60;

        long[] bestTimes = new long[nodeCount * DAY_SLOTS];
        Arrays.fill(bestTimes, Long.MAX_VALUE);
        PriorityQueue<EstadoRuta> pq = new PriorityQueue<>(Comparator.comparingLong(s -> s.arrivalMin));

        long horizonDays = MAX_HORIZON_MIN / DAY_MIN;
        for (long d = 0; d <= horizonDays; d++) {
            long startMin = readyMin + d * DAY_MIN;
            bestTimes[startIdx * DAY_SLOTS + (int) d] = startMin;
            pq.add(new EstadoRuta(startIdx, startMin, -1L, null, null));
        }

        while (!pq.isEmpty()) {
            EstadoRuta current = pq.poll();
            if (current.nodeIdx == targetIdx) {
                long transitMinutes = (current.arrivalMin + DEST_STORAGE_MIN) - readyMin;
                if (transitMinutes > slaMaxMinutes) return null;   // la mejor llegada ya es tardÃ­a
                int[] sk = new int[current.legs];
                int i = current.legs - 1;
                for (EstadoRuta s = current; s.edge != null; s = s.parent) sk[i--] = s.edge.idx;
                return sk;
            }
            if (current.legs >= MAX_CANDIDATE_LEGS) continue;
            for (Arista flight : adjByIdx[current.nodeIdx]) {
                int nextIdx = (flight.to == null) ? -1 : flight.to.idx;
                if (nextIdx < 0) continue;
                if (nextIdx != targetIdx && esHub(nextIdx)) continue;   // no transitar por hubs
                long minWait  = (current.edge == null) ? 0L : CONNECTION_MIN;
                long actualDep = nextDepartureMin(flight.depMinuteOfDay, current.arrivalMin + minWait);
                long actualArr = actualDep + flight.durationMinutes;
                long dayOffset = actualArr / DAY_MIN - readyDay;
                if (dayOffset < 0 || dayOffset >= DAY_SLOTS) continue;
                if (actualArr - readyMin > MAX_HORIZON_MIN) continue;
                // capacity-free: NO se chequea vuelo ni almacÃ©n (plantilla cross-bloque).
                int cell = nextIdx * DAY_SLOTS + (int) dayOffset;
                if (actualArr < bestTimes[cell]) {
                    bestTimes[cell] = actualArr;
                    pq.add(new EstadoRuta(nextIdx, actualArr, actualDep, flight, current));
                }
            }
        }
        return null;
    }

    public void reSeedHubAvoiding(int maxClaves, long deadlineNs) {
        if (maxClaves <= 0 || rutaSkeletonCache.isEmpty()) return;
        int procesadas = 0;
        for (Map.Entry<Long, List<int[]>> e : rutaSkeletonCache.entrySet()) {
            if (procesadas >= maxClaves || System.nanoTime() >= deadlineNs) break;
            long key = e.getKey();
            if (!reSeeded.add(key)) continue;        // ya intentada
            procesadas++;
            int startIdx   = (int) (key >>> 40);
            int targetIdx  = (int) ((key >>> 24) & 0xFFFFL);
            int hourBucket = (int) ((key >>> 8) & 0xFFFFL);
            int slaHours   = (int) (key & 0xFFL);
            int[] sk = esqueletoEvitandoHubs(startIdx, targetIdx, hourBucket * SKELETON_BUCKET_MIN, slaHours);
            if (sk == null || sk.length == 0) continue;
            List<int[]> lista = e.getValue();
            if (lista == null || lista.size() >= MAX_SKELETONS_POR_CLAVE) continue;
            boolean existe = false;
            for (int[] s : lista) if (Arrays.equals(s, sk)) { existe = true; break; }
            if (!existe) lista.add(sk);
        }
    }

    // -----------------------------------------------------------------------
    // Interface OperadorReparacion
    // -----------------------------------------------------------------------

    @Override
    public void repair(SolucionAlns solution, List<LoteEnvio> unassigned,
                       Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        for (LoteEnvio batch : unassigned) {
            ResultadoRuta result = findShortestPath(batch, blockFlight, blockAirport);
            batch.setAssignedRoute(result.edges);
            batch.setAssignedDepartures(result.actualDepartures);
            batch.setCumpleSLA(result.cumpleSLA);
            applyToBlock(batch, result, blockFlight, blockAirport);
        }
    }

    // -----------------------------------------------------------------------
    // MÃ©todos de gestiÃ³n de ocupaciÃ³n por bloque
    // -----------------------------------------------------------------------

    public void releaseFromBlock(LoteEnvio batch,
                                  Map<Long, Integer> blockFlight,
                                  Map<Long, Integer> blockAirport) {
        List<Arista> route = batch.getAssignedRoute();
        List<Long> deps  = batch.getAssignedDepartures();
        if (route == null || route.isEmpty() || deps == null || deps.isEmpty()) return;

        for (int i = 0; i < route.size(); i++) {
            Arista e      = route.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.durationMinutes;

            blockFlight.merge(flightKey(e.idx, depMin), -batch.getQuantity(), Integer::sum);

            boolean esFinalLeg = (i == route.size() - 1);
            // Libera exactamente los slots de estadÃ­a que cargÃ³ applyToBlock.
            if (!esFinalLeg && e.to.idx >= 0) {
                cargarAlmacenPierna(blockAirport, e.to.idx, arrMin, deps.get(i + 1),
                        -batch.getQuantity());
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                cargarAlmacenPierna(blockAirport, e.to.idx, arrMin, arrMin + DEST_STORAGE_MIN,
                        -batch.getQuantity());
            }
        }
        // Libera la ocupaciÃ³n de origen (espejo de applyToBlock).
        cargarOrigen(blockAirport, batch, route, deps, -1);
    }

    public boolean addCancelledFlight(long flightKey) {
        return cancelledFlightDays.add(flightKey);
    }

    public boolean isCancelledFlight(long flightKey) {
        return cancelledFlightDays.contains(flightKey);
    }

    public boolean rutaUsaVueloCancelado(LoteEnvio batch) {
        if (batch == null || cancelledFlightDays.isEmpty()) return false;
        List<Arista> route = batch.getAssignedRoute();
        List<Long> deps  = batch.getAssignedDepartures();
        if (route == null || route.isEmpty() || deps == null || deps.size() != route.size()) {
            return false;
        }
        for (int i = 0; i < route.size(); i++) {
            if (cancelledFlightDays.contains(flightKey(route.get(i).idx, deps.get(i)))) {
                return true;
            }
        }
        return false;
    }

    private void cargarOrigen(Map<Long, Integer> mapa, LoteEnvio batch,
                             List<Arista> edges, List<Long> deps, int signo) {
        if (edges == null || edges.isEmpty() || deps == null || deps.isEmpty()) return;
        Nodo origen = edges.get(0).from;
        if (origen == null || origen.idx < 0 || origen.capacity <= 0) return;
        long desde = toEpochMin(batch.getReadyTime());
        long firstDep = deps.get(0);
        if (firstDep <= desde) return;
        cargarAlmacenPierna(mapa, origen.idx, desde, firstDep, signo * batch.getQuantity());
    }

    private boolean cabeOrigen(LoteEnvio batch, List<Arista> edges, List<Long> deps,
                               Map<Long, Integer> blockAirport) {
        if (edges == null || edges.isEmpty() || deps == null || deps.isEmpty()) return true;
        Nodo origen = edges.get(0).from;
        if (origen == null || origen.idx < 0 || origen.capacity <= 0) return true;
        long desde = toEpochMin(batch.getReadyTime());
        long firstDep = deps.get(0);
        if (firstDep <= desde) return true;
        return cabeAlmacenPierna(origen, desde, firstDep, batch.getQuantity(), blockAirport);
    }

    // -----------------------------------------------------------------------
    // OcupaciÃ³n de origen por el backlog (envÃ­os sinRuta en espera)
    // -----------------------------------------------------------------------

    public LoteEnvio reconstruirEsperaOrigenBacklog(Collection<LoteEnvio> pendientes,
                                                       Collection<LoteEnvio> bloqueLote) {
        if (bloqueLote != null) {
            for (LoteEnvio b : bloqueLote) {
                if (b == null || b.getReadyTime() == null) continue;
                relojUtcMin = Math.max(relojUtcMin, toEpochMin(b.getReadyTime()));
            }
        }
        backlogOrigenOcc.clear();
        origenAdmitidos.clear();
        if (pendientes == null || relojUtcMin == Long.MIN_VALUE) return null;

        List<LoteEnvio> orden = new ArrayList<>();
        for (LoteEnvio b : pendientes) {
            if (b == null || b.getReadyTime() == null) continue;
            if (b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty()) continue; // con ruta â‡’ ya contabilizado
            orden.add(b);
        }
        orden.sort(Comparator.comparingLong(b -> toEpochMin(b.readyEfectivo())));
        LoteEnvio desbordado = null;
        for (LoteEnvio b : orden) {
            if (cabeEsperaOrigen(b)) {
                acumularEsperaOrigen(b, +1);
                origenAdmitidos.add(b.getId());
            } else if (desbordado == null) {
                desbordado = b;   // primera maleta que no cabe en su origen â‡’ colapso
            }
        }
        return desbordado;
    }

    public void removerEsperaOrigenBacklog(LoteEnvio batch) {
        if (batch == null) return;
        if (batch.getAssignedRoute() != null && !batch.getAssignedRoute().isEmpty()) return;
        if (origenAdmitidos.remove(batch.getId())) {
            acumularEsperaOrigen(batch, -1);
        }
    }

    private boolean cabeEsperaOrigen(LoteEnvio batch) {
        Nodo origen = graph.nodes.get(batch.origenEfectivo());
        if (origen == null || origen.idx < 0 || origen.capacity <= 0) return true;
        long desde = toEpochMin(batch.readyEfectivo());
        if (relojUtcMin <= desde) return true;
        return cabeAlmacenPierna(origen, desde, relojUtcMin, batch.getQuantity(), Map.of());
    }

    private void acumularEsperaOrigen(LoteEnvio batch, int signo) {
        if (batch == null || batch.readyEfectivo() == null || relojUtcMin == Long.MIN_VALUE) return;
        Nodo origen = graph.nodes.get(batch.origenEfectivo());
        if (origen == null || origen.idx < 0 || origen.capacity <= 0) return;
        long desde = toEpochMin(batch.readyEfectivo());
        if (relojUtcMin <= desde) return;
        cargarAlmacenPierna(backlogOrigenOcc, origen.idx, desde, relojUtcMin, signo * batch.getQuantity());
    }

    public void commitBlock(Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        blockFlight.forEach((key, qty) -> {
            if (qty != 0) flightOccupancy.merge(key, qty, Integer::sum);
        });
        blockAirport.forEach((key, qty) -> {
            if (qty != 0) airportOccupancy.merge(key, qty, Integer::sum);
        });
        // Redescubrir hubs desde la ocupaciÃ³n real cada N bloques (todos los escenarios).
        // Fuera del bucle caliente por-batch â†’ Ta-safe; el conjunto solo crece (sin flapping).
        if (++commitsDesdeReclasificar >= HUB_RECLASIFICAR_CADA) {
            commitsDesdeReclasificar = 0;
            reclasificarHubsPorUtilizacion(umbralHubPico);
        }
    }

    public void releaseFromGlobal(LoteEnvio batch) {
        List<Arista> route = batch.getAssignedRoute();
        List<Long> deps  = batch.getAssignedDepartures();
        if (route == null || route.isEmpty() || deps == null || deps.isEmpty()) return;

        for (int i = 0; i < route.size(); i++) {
            Arista e      = route.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.durationMinutes;

            flightOccupancy.merge(flightKey(e.idx, depMin), -batch.getQuantity(), Integer::sum);

            boolean esFinalLeg = (i == route.size() - 1);
            // Libera por slots de estadÃ­a (mismo intervalo que cargÃ³ applyToBlock).
            if (!esFinalLeg && e.to.idx >= 0) {
                cargarAlmacenPierna(airportOccupancy, e.to.idx, arrMin, deps.get(i + 1),
                        -batch.getQuantity());
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                cargarAlmacenPierna(airportOccupancy, e.to.idx, arrMin, arrMin + DEST_STORAGE_MIN,
                        -batch.getQuantity());
            }
        }
        // Libera la ocupaciÃ³n de origen en la ocupaciÃ³n global (espejo de applyToBlock).
        cargarOrigen(airportOccupancy, batch, route, deps, -1);
    }

    public void releaseSuffixFromGlobal(LoteEnvio batch, int k) {
        List<Arista> route = batch.getAssignedRoute();
        List<Long> deps  = batch.getAssignedDepartures();
        if (route == null || deps == null || deps.size() != route.size()) return;
        int n = route.size();
        if (k <= 0 || k >= n) return;

        // EstadÃ­a vieja del nodo de corte k-1: [arr_{k-1}, deps[k]).
        Arista corte = route.get(k - 1);
        if (corte.to != null && corte.to.idx >= 0) {
            long arrCorte = deps.get(k - 1) + corte.durationMinutes;
            cargarAlmacenPierna(airportOccupancy, corte.to.idx, arrCorte, deps.get(k),
                    -batch.getQuantity());
        }
        // Sufijo: vuelos y estadÃ­as de destino, desde k.
        for (int i = k; i < n; i++) {
            Arista e      = route.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.durationMinutes;
            flightOccupancy.merge(flightKey(e.idx, depMin), -batch.getQuantity(), Integer::sum);
            boolean esFinalLeg = (i == n - 1);
            if (!esFinalLeg && e.to.idx >= 0) {
                cargarAlmacenPierna(airportOccupancy, e.to.idx, arrMin, deps.get(i + 1),
                        -batch.getQuantity());
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                cargarAlmacenPierna(airportOccupancy, e.to.idx, arrMin, arrMin + DEST_STORAGE_MIN,
                        -batch.getQuantity());
            }
        }
    }

    public boolean cumpleSlaDesdeOrigen(RutaCandidata sufijo, LoteEnvio original) {
        if (sufijo == null || original == null) return false;
        long deadlineMin = toEpochMin(original.getReadyTime()) + (long) original.getSlaLimitHours() * 60L;
        return (sufijo.getArrivalMin() + DEST_STORAGE_MIN) <= deadlineMin;
    }

    // -----------------------------------------------------------------------
    // Dijkstra earliest-arrival con capacidad global + bloque
    // -----------------------------------------------------------------------

    private ResultadoRuta findShortestPath(LoteEnvio batch,
                                          Map<Long, Integer> blockFlight,
                                          Map<Long, Integer> blockAirport) {
        return findShortestPath(batch, blockFlight, blockAirport, false);
    }

    public boolean sinRutaPorAlmacenLleno(LoteEnvio batch) {
        if (batch == null) return false;
        ResultadoRuta con = findShortestPath(batch, Map.of(), Map.of(), false);
        if (con.cumpleSLA && !con.edges.isEmpty()) return false;  // sÃ­ habÃ­a ruta on-time â†’ no fue almacÃ©n
        ResultadoRuta sin = findShortestPath(batch, Map.of(), Map.of(), true);
        return sin.cumpleSLA && !sin.edges.isEmpty();
    }

    private ResultadoRuta findShortestPath(LoteEnvio batch,
                                          Map<Long, Integer> blockFlight,
                                          Map<Long, Integer> blockAirport,
                                          boolean ignorarAlmacen) {
        Nodo startNodeObj  = graph.nodes.get(batch.getOriginCode());
        Nodo targetNodeObj = graph.nodes.get(batch.getDestCode());
        if (startNodeObj == null || targetNodeObj == null) return ResultadoRuta.EMPTY;

        int startIdx      = startNodeObj.idx;
        int targetNodeIdx = targetNodeObj.idx;
        if (startIdx < 0 || targetNodeIdx < 0) return ResultadoRuta.EMPTY;

        long readyMin      = toEpochMin(batch.getReadyTime());
        long readyDay      = readyMin / DAY_MIN;
        long slaMaxMinutes = (long) batch.getSlaLimitHours() * 60;

        long[] bestTimes = new long[nodeCount * DAY_SLOTS];
        Arrays.fill(bestTimes, Long.MAX_VALUE);

        PriorityQueue<EstadoRuta> pq = new PriorityQueue<>(Comparator.comparingLong(s -> s.arrivalMin));

        long horizonDays = MAX_HORIZON_MIN / DAY_MIN;
        for (long d = 0; d <= horizonDays; d++) {
            long startMin = readyMin + d * DAY_MIN;
            bestTimes[startIdx * DAY_SLOTS + (int)d] = startMin;
            pq.add(new EstadoRuta(startIdx, startMin, -1L, null, null));
        }

        while (!pq.isEmpty()) {
            EstadoRuta current = pq.poll();

            if (current.nodeIdx == targetNodeIdx) {
                List<Arista> edges = new ArrayList<>();
                List<Long> deps  = new ArrayList<>();
                for (EstadoRuta s = current; s.edge != null; s = s.parent) {
                    edges.add(0, s.edge);
                    deps.add(0, s.depMin);
                }
                if (!ignorarAlmacen
                        && !cabeEstadiasRuta(edges, deps, batch.getQuantity(), blockAirport)) {
                    continue;
                }
                long transitMinutes = (current.arrivalMin + DEST_STORAGE_MIN) - readyMin;
                return new ResultadoRuta(edges, deps, transitMinutes <= slaMaxMinutes);
            }

            for (Arista flight : adjByIdx[current.nodeIdx]) {
                long minWait  = (current.edge == null) ? 0L : CONNECTION_MIN;
                long earliest = current.arrivalMin + minWait;

                long actualDep = nextDepartureMin(flight.depMinuteOfDay, earliest);
                long actualArr = actualDep + flight.durationMinutes;

                long dayOffset = actualArr / DAY_MIN - readyDay;
                if (dayOffset < 0 || dayOffset >= DAY_SLOTS) continue;
                if (actualArr - readyMin > MAX_HORIZON_MIN) continue;

                if (!ignorarAlmacen && current.edge == null
                        && !cabeAlmacenPierna(startNodeObj, readyMin, actualDep,
                                batch.getQuantity(), blockAirport)) continue;

                if (remainingFlight(flight, actualDep, blockFlight) < batch.getQuantity()) continue;

                int nextIdx = flight.to.idx;
                if (nextIdx < 0) continue;
                if (!ignorarAlmacen && flight.to.capacity > 0) {
                    int qty = batch.getQuantity();
                    long ak = airportKey(nextIdx, actualArr);
                    if (airportOccupancy.getOrDefault(ak, 0) + blockAirport.getOrDefault(ak, 0)
                            + backlogOrigenOcc.getOrDefault(ak, 0) + qty
                            > flight.to.capacity)
                        continue;
                }

                int cell = nextIdx * DAY_SLOTS + (int)dayOffset;
                if (actualArr < bestTimes[cell]) {
                    bestTimes[cell] = actualArr;
                    pq.add(new EstadoRuta(nextIdx, actualArr, actualDep, flight, current));
                }
            }
        }

        return ResultadoRuta.EMPTY;
    }

    public List<RutaCandidata> generarCandidatosRuta(LoteEnvio batch,
                                                       Map<Long, Integer> blockFlight,
                                                       Map<Long, Integer> blockAirport,
                                                       int maxCandidatos) {
        if (batch == null || maxCandidatos <= 0) return Collections.emptyList();

        Nodo startNodeObj = graph.nodes.get(batch.getOriginCode());
        Nodo targetNodeObj = graph.nodes.get(batch.getDestCode());
        if (startNodeObj == null || targetNodeObj == null) return Collections.emptyList();

        int startIdx = startNodeObj.idx;
        int targetIdx = targetNodeObj.idx;
        if (startIdx < 0 || targetIdx < 0) return Collections.emptyList();

        long readyMin = toEpochMin(batch.getReadyTime());
        long readyDay = readyMin / DAY_MIN;
        long slaMaxMinutes = (long) batch.getSlaLimitHours() * 60L;

        long skKey = skeletonKey(startIdx, targetIdx, readyMin, batch.getSlaLimitHours());
        List<int[]> cachedSk = rutaSkeletonCache.get(skKey);
        if (cachedSk != null && !cachedSk.isEmpty()) {
            List<RutaCandidata> reuso = new ArrayList<>(cachedSk.size());
            Set<String> firmasReuso = new HashSet<>();
            for (int[] sk : cachedSk) {
                RutaCandidata c = materializarSkeleton(batch, sk, blockFlight, blockAirport);
                if (c == null) continue;
                if (firmasReuso.add(c.signature())) reuso.add(c);
            }
            if (reuso.size() >= maxCandidatos) {
                reuso.sort(OperadorReparacionVoraz::compareRouteCandidates);
                if (reuso.get(0).cumpleSLA) {
                    return reuso.size() <= maxCandidatos ? reuso
                            : new ArrayList<>(reuso.subList(0, maxCandidatos));
                }
            }
        }

        int labelsPorCelda = Math.max(1, Math.min(4, maxCandidatos + 1));
        @SuppressWarnings("unchecked")
        List<EtiquetaRuta>[] labels = new List[nodeCount * DAY_SLOTS];

        PriorityQueue<EstadoRuta> pq = new PriorityQueue<>(
                Comparator.comparingLong((EstadoRuta s) -> s.arrivalMin)
                        .thenComparingInt(s -> s.legs));

        long horizonDays = MAX_HORIZON_MIN / DAY_MIN;
        for (long d = 0; d <= horizonDays; d++) {
            long startMin = readyMin + d * DAY_MIN;
            int cell = startIdx * DAY_SLOTS + (int) d;
            EtiquetaRuta label = new EtiquetaRuta(startMin, 0, 0.0);
            addLabel(labels, cell, label, labelsPorCelda);
            pq.add(new EstadoRuta(startIdx, startMin, -1L, null, null, 0, 0.0));
        }

        int limiteObjetivo = maxCandidatos <= 1 ? 1 : Math.max(maxCandidatos, maxCandidatos * 2);
        int maxExpansiones = Math.max(256, maxCandidatos * Math.max(1, nodeCount) * DAY_SLOTS * 8);
        int expansiones = 0;
        int candidatosOnTime = 0;
        List<RutaCandidata> candidatos = new ArrayList<>(limiteObjetivo);
        Set<String> firmas = new HashSet<>();

        while (!pq.isEmpty() && candidatos.size() < limiteObjetivo && expansiones++ < maxExpansiones) {
            EstadoRuta current = pq.poll();

            if (current.nodeIdx == targetIdx && current.edge != null) {
                RutaCandidata candidate = toRouteCandidate(current, batch, readyMin, slaMaxMinutes,
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

            for (Arista flight : adjByIdx[current.nodeIdx]) {
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
                // Primer vuelo viable solo si la espera en almacÃ©n de origen cabe.
                if (current.edge == null
                        && !cabeAlmacenPierna(startNodeObj, readyMin, actualDep,
                                batch.getQuantity(), blockAirport)) continue;
                if (remainingFlight(flight, actualDep, blockFlight) < batch.getQuantity()) continue;
                if (!hasAirportCapacity(flight.to, nextIdx == targetIdx, actualArr, batch.getQuantity(), blockAirport)) {
                    continue;
                }

                int cell = nextIdx * DAY_SLOTS + (int) dayOffset;
                double pressure = Math.max(current.pressure, projectedStepPressure(
                        flight, actualDep, nextIdx == targetIdx, batch.getQuantity(), blockFlight, blockAirport));
                EtiquetaRuta label = new EtiquetaRuta(actualArr, current.legs + 1, pressure);
                if (isDominated(labels[cell], label)) continue;
                addLabel(labels, cell, label, labelsPorCelda);
                pq.add(new EstadoRuta(nextIdx, actualArr, actualDep, flight, current,
                        current.legs + 1, pressure));
            }
        }

        candidatos.sort(OperadorReparacionVoraz::compareRouteCandidates);

        // Guardar los esqueletos hallados para reusarlos en bloques futuros.
        if (!candidatos.isEmpty()) {
            List<int[]> sks = new ArrayList<>(Math.min(candidatos.size(), MAX_SKELETONS_POR_CLAVE));
            for (RutaCandidata c : candidatos) {
                if (sks.size() >= MAX_SKELETONS_POR_CLAVE) break;
                List<Arista> es = c.getEdges();
                int[] arr = new int[es.size()];
                for (int i = 0; i < es.size(); i++) arr[i] = es.get(i).idx;
                sks.add(arr);
            }
            rutaSkeletonCache.put(skKey, sks);
        }

        if (candidatos.size() <= maxCandidatos) return candidatos;
        return new ArrayList<>(candidatos.subList(0, maxCandidatos));
    }

    public int precalentarEsqueletos(Iterable<LoteEnvio> batches, int maxCandidatos) {
        return precalentarEsqueletos(batches, maxCandidatos, null);
    }

    public int precalentarEsqueletos(Iterable<LoteEnvio> batches, int maxCandidatos,
                                     BooleanSupplier cancelado) {
        if (batches == null || maxCandidatos <= 0) return 0;
        Map<Long, Integer> bf = new HashMap<>();   // mapas de bloque vacÃ­os: generarCandidatosRuta solo los lee
        Map<Long, Integer> ba = new HashMap<>();
        Set<Long> vistas = new HashSet<>();
        int calentadas = 0;
        for (LoteEnvio b : batches) {
            if (cancelado != null && cancelado.getAsBoolean()) break;
            if (b == null || b.getReadyTime() == null) continue;
            Nodo o = graph.nodes.get(b.getOriginCode());
            Nodo d = graph.nodes.get(b.getDestCode());
            if (o == null || d == null || o.idx < 0 || d.idx < 0) continue;
            long key = skeletonKey(o.idx, d.idx, toEpochMin(b.getReadyTime()), b.getSlaLimitHours());
            if (!vistas.add(key)) continue;                  // un intento por clave Ãºnica
            generarCandidatosRuta(b, bf, ba, maxCandidatos); // popula rutaSkeletonCache en el miss
            calentadas++;
        }
        return calentadas;
    }

    static long skeletonKey(int startIdx, int targetIdx, long readyMin, int slaHours) {   // package-private para tests
        long hourBucket = (readyMin % DAY_MIN) / SKELETON_BUCKET_MIN;   // 0..23
        return ((long) startIdx << 40)
                | ((long) targetIdx << 24)
                | (hourBucket << 8)
                | (slaHours & 0xFFL);
    }

    private RutaCandidata materializarSkeleton(LoteEnvio batch,
                                                int[] edgeIdxs,
                                                Map<Long, Integer> blockFlight,
                                                Map<Long, Integer> blockAirport) {
        if (edgeIdxs == null || edgeIdxs.length == 0) return null;
        List<Arista> ruta = new ArrayList<>(edgeIdxs.length);
        for (int idx : edgeIdxs) {
            if (idx < 0 || idx >= edgeByIdx.length) return null;
            Arista e = edgeByIdx[idx];
            if (e == null) return null;
            ruta.add(e);
        }
        return materializarRutaCandidata(batch, ruta, blockFlight, blockAirport);
    }

    // -----------------------------------------------------------------------
    // Helpers privados
    // -----------------------------------------------------------------------

    private void applyToBlock(LoteEnvio batch, ResultadoRuta result,
                               Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        for (int i = 0; i < result.edges.size(); i++) {
            Arista e      = result.edges.get(i);
            long depMin = result.actualDepartures.get(i);
            long arrMin = depMin + e.durationMinutes;

            blockFlight.merge(flightKey(e.idx, depMin), batch.getQuantity(), Integer::sum);

            boolean esFinalLeg = (i == result.edges.size() - 1);
            // Carga por OCUPACIÃ“N concurrente: cada slot de la estadÃ­a real de la pierna.
            if (!esFinalLeg && e.to.idx >= 0) {
                long salida = result.actualDepartures.get(i + 1);   // hasta que sale el siguiente vuelo
                cargarAlmacenPierna(blockAirport, e.to.idx, arrMin, salida, batch.getQuantity());
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                // Destino final: la maleta se retira ~DEST_STORAGE_MIN tras aterrizar (1 slot).
                cargarAlmacenPierna(blockAirport, e.to.idx, arrMin, arrMin + DEST_STORAGE_MIN,
                        batch.getQuantity());
            }
        }
        cargarOrigen(blockAirport, batch, result.edges, result.actualDepartures, +1);
    }

    private long nextDepartureMin(int depMinuteOfDay, long earliest) {
        long dayStart  = (earliest / DAY_MIN) * DAY_MIN;
        long candidate = dayStart + depMinuteOfDay;
        return candidate < earliest ? candidate + DAY_MIN : candidate;
    }

    private int remainingFlight(Arista flight, long depMin, Map<Long, Integer> blockFlight) {
        long key = flightKey(flight.idx, depMin);
        if (cancelledFlightDays.contains(key)) return 0;  // vuelo cancelado ese dÃ­a
        return flight.capacity
             - flightOccupancy.getOrDefault(key, 0)
             - blockFlight.getOrDefault(key, 0);
    }

    public long calcularProximaSalida(int depMinuteOfDay, long earliest) {
        return nextDepartureMin(depMinuteOfDay, earliest);
    }

    public int capacidadRestante(Arista flight, long depMin, Map<Long, Integer> blockFlight) {
        return remainingFlight(flight, depMin, blockFlight);
    }

    public int capacidadAlmacen(Nodo node, long arrMin, Map<Long, Integer> blockAirport) {
        if (node == null || node.idx < 0 || node.capacity <= 0) return Integer.MAX_VALUE;
        long key = airportKey(node.idx, arrMin);
        return node.capacity
             - airportOccupancy.getOrDefault(key, 0)
             - blockAirport.getOrDefault(key, 0)
             - backlogOrigenOcc.getOrDefault(key, 0);
    }

    public int ocupacionGlobalVuelo(long flightKey) {
        return flightOccupancy.getOrDefault(flightKey, 0);
    }

    public int ocupacionGlobalAlmacen(long slotKey) {
        return airportOccupancy.getOrDefault(slotKey, 0)
             + backlogOrigenOcc.getOrDefault(slotKey, 0);
    }

    public void aplicarAsignacionBloque(LoteEnvio batch,
                                         Map<Long, Integer> blockFlight,
                                         Map<Long, Integer> blockAirport) {
        List<Arista> route = batch.getAssignedRoute();
        List<Long> deps  = batch.getAssignedDepartures();
        if (route == null || route.isEmpty() || deps == null || deps.size() != route.size()) return;
        ResultadoRuta fake = new ResultadoRuta(route, deps, batch.isCumpleSLA());
        applyToBlock(batch, fake, blockFlight, blockAirport);
    }

    public static long toEpochMinPublic(LocalDateTime dt) {
        return toEpochMin(dt);
    }

    public boolean intentarDijkstraDirecto(LoteEnvio batch,
                                            Map<Long, Integer> blockFlight,
                                            Map<Long, Integer> blockAirport) {
        List<RutaCandidata> candidates = generarCandidatosRuta(batch, blockFlight, blockAirport, 1);
        if (candidates.isEmpty()) return false;
        aplicarCandidatoRuta(batch, candidates.get(0));
        return true;
    }

    public RutaCandidata materializarRutaCandidata(LoteEnvio batch,
                                                    List<Arista> ruta,
                                                    Map<Long, Integer> blockFlight,
                                                    Map<Long, Integer> blockAirport) {
        if (batch == null || ruta == null || ruta.isEmpty()) return null;

        long readyMin = toEpochMin(batch.getReadyTime());
        long slaMaxMinutes = (long) batch.getSlaLimitHours() * 60L;
        long earliest = readyMin;
        String expectedFrom = batch.getOriginCode();
        List<Arista> edges = new ArrayList<>(ruta.size());
        List<Long> deps = new ArrayList<>(ruta.size());

        for (int i = 0; i < ruta.size(); i++) {
            Arista edge = ruta.get(i);
            if (edge == null || edge.from == null || edge.to == null) return null;
            if (!Objects.equals(expectedFrom, edge.from.code)) return null;

            boolean finalLeg = i == ruta.size() - 1;
            if (finalLeg && !Objects.equals(batch.getDestCode(), edge.to.code)) return null;

            long minDeparture = (i == 0) ? earliest : earliest + CONNECTION_MIN;
            long depMin = nextDepartureMin(edge.depMinuteOfDay, minDeparture);
            long arrMin = depMin + edge.durationMinutes;
            boolean found = false;
            while (arrMin - readyMin <= MAX_HORIZON_MIN) {
                boolean origenOk = i != 0
                        || cabeAlmacenPierna(edge.from, readyMin, depMin,
                                batch.getQuantity(), blockAirport);
                if (origenOk
                        && remainingFlight(edge, depMin, blockFlight) >= batch.getQuantity()
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

    public void aplicarCandidatoRuta(LoteEnvio batch, RutaCandidata candidate) {
        if (batch == null || candidate == null) return;
        batch.setAssignedRoute(new ArrayList<>(candidate.edges));
        batch.setAssignedDepartures(new ArrayList<>(candidate.actualDepartures));
        batch.setCumpleSLA(candidate.cumpleSLA);
    }

    public void aplicarCandidatoBloque(LoteEnvio batch,
                                       RutaCandidata candidate,
                                       Map<Long, Integer> blockFlight,
                                       Map<Long, Integer> blockAirport) {
        if (batch == null || candidate == null) return;
        ResultadoRuta fake = new ResultadoRuta(candidate.edges, candidate.actualDepartures, candidate.cumpleSLA);
        applyToBlock(batch, fake, blockFlight, blockAirport);
    }

    public boolean rutaSirveParaBatch(RutaCandidata candidate,
                                      LoteEnvio batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport) {
        return rutaSirveParaBatch(candidate, batch, blockFlight, blockAirport, 0.0, 0.0);
    }

    public boolean rutaSirveParaBatch(RutaCandidata candidate,
                                      LoteEnvio batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport,
                                      double reservaBase) {
        return rutaSirveParaBatch(candidate, batch, blockFlight, blockAirport, reservaBase, 0.0);
    }

    public boolean rutaSirveParaBatch(RutaCandidata candidate,
                                      LoteEnvio batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport,
                                      double reservaBase,
                                      double reservaAlmacenBase) {
        if (candidate == null || batch == null) return false;
        List<Arista> edges = candidate.getEdges();
        List<Long> deps = candidate.getActualDepartures();
        if (edges.isEmpty() || deps.size() != edges.size()) return false;

        long readyMin = toEpochMin(batch.getReadyTime());
        if (deps.get(0) < readyMin) return false;   // el envÃ­o aÃºn no estaba listo

        long arrMin = deps.get(deps.size() - 1) + edges.get(edges.size() - 1).durationMinutes;
        long transitMin = (arrMin + DEST_STORAGE_MIN) - readyMin;
        if (transitMin > (long) batch.getSlaLimitHours() * 60L) return false;   // tardarÃ­a

        // ColchÃ³n de reserva proporcional a la holgura (urgente â‡’ 0 â‡’ sin reserva).
        double slackRatio = 0.0;
        if (reservaBase > 0.0 || reservaAlmacenBase > 0.0) {
            double slaMin = Math.max(1.0, batch.getSlaLimitHours() * 60.0);
            slackRatio = Math.max(0.0, Math.min(1.0, candidate.getSlackMin() / slaMin));
        }
        double reservaVuelo = reservaBase * slackRatio;
        double reservaAlmacen = reservaAlmacenBase * slackRatio;

        int qty = batch.getQuantity();
        for (int i = 0; i < edges.size(); i++) {
            Arista e = edges.get(i);
            long depMin = deps.get(i);
            int colchonVuelo = reservaVuelo > 0.0 && e.capacity > 0
                    ? (int) Math.ceil(reservaVuelo * e.capacity) : 0;
            if (remainingFlight(e, depMin, blockFlight) < qty + colchonVuelo) return false;
            boolean finalLeg = (i == edges.size() - 1);
            long llegada = depMin + e.durationMinutes;
            // Capacidad CONCURRENTE: la estadÃ­a real ocupa [llegada, salida); cada slot
            // del intervalo debe caber. Destino final â‰ˆ DEST_STORAGE_MIN; escala = hasta salir.
            long salida = finalLeg ? llegada + DEST_STORAGE_MIN : deps.get(i + 1);
            if (!cabeAlmacenPierna(e.to, llegada, salida, qty, blockAirport)) {
                return false;
            }
            // ColchÃ³n en almacÃ©n de HUB para la estadÃ­a de una ESCALA (no destino final).
            // Protege el storage concurrente de hub para los envÃ­os urgentes/24h.
            if (!finalLeg && reservaAlmacen > 0.0 && e.to != null && e.to.capacity > 0
                    && esHub(e.to.idx)) {
                int colchonAlm = (int) Math.ceil(reservaAlmacen * e.to.capacity);
                if (!cabeAlmacenPierna(e.to, llegada, salida, qty + colchonAlm, blockAirport)) {
                    return false;
                }
            }
        }
        // La espera en el almacÃ©n de origen tambiÃ©n debe caber.
        if (!cabeOrigen(batch, edges, deps, blockAirport)) return false;
        return true;
    }

    public Set<Long> clavesOcupadas(RutaCandidata candidate, LoteEnvio batch) {
        if (candidate == null) return Collections.emptySet();
        List<Arista> edges = candidate.getEdges();
        List<Long> deps = candidate.getActualDepartures();
        if (edges.isEmpty() || deps.size() != edges.size()) return Collections.emptySet();

        Set<Long> keys = new HashSet<>(edges.size() * 3);
        // Incluye los slots de espera en el almacÃ©n de origen.
        if (batch != null) {
            Nodo origen = edges.get(0).from;
            if (origen != null && origen.idx >= 0 && origen.capacity > 0) {
                long desde = toEpochMin(batch.getReadyTime());
                long firstDep = deps.get(0);
                if (firstDep > desde) agregarSlotsEstadia(keys, origen.idx, desde, firstDep);
            }
        }
        for (int i = 0; i < edges.size(); i++) {
            Arista e = edges.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.durationMinutes;

            keys.add(flightKey(e.idx, depMin));

            boolean esFinalLeg = (i == edges.size() - 1);
            // Claves por SLOT de la estadÃ­a concurrente (mismo intervalo que applyToBlock).
            if (!esFinalLeg && e.to.idx >= 0) {
                agregarSlotsEstadia(keys, e.to.idx, arrMin, deps.get(i + 1));
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                agregarSlotsEstadia(keys, e.to.idx, arrMin, arrMin + DEST_STORAGE_MIN);
            }
        }
        return keys;
    }

    private static long flightKey(int edgeIdx, long epochMin) {
        return CodificadorClaveVuelo.flightKey(edgeIdx, epochMin);
    }

    private static long airportKey(int nodeIdx, long epochMin) {
        return slotKey(nodeIdx, epochMin / STORAGE_SLOT_MIN);
    }

    private static long slotKey(int nodeIdx, long slot) {
        return (((long) nodeIdx) << DAY_BITS) | (slot & CodificadorClaveVuelo.DAY_MASK);
    }

    public static long claveAlmacenDeSlot(int nodeIdx, long epochMin) {
        return airportKey(nodeIdx, epochMin);
    }

    private static long ultimoSlot(long llegada, long salida) {
        return (Math.max(llegada + 1, salida) - 1) / STORAGE_SLOT_MIN;
    }

    private void agregarSlotsEstadia(Set<Long> keys, int nodeIdx, long llegada, long salida) {
        if (nodeIdx < 0) return;
        long s1 = ultimoSlot(llegada, salida);
        for (long s = llegada / STORAGE_SLOT_MIN; s <= s1; s++) {
            keys.add(slotKey(nodeIdx, s));
        }
    }

    private void cargarAlmacenPierna(Map<Long, Integer> mapa, int nodeIdx,
                                     long llegada, long salida, int delta) {
        if (nodeIdx < 0) return;
        long s1 = ultimoSlot(llegada, salida);
        for (long s = llegada / STORAGE_SLOT_MIN; s <= s1; s++) {
            mapa.merge(slotKey(nodeIdx, s), delta, Integer::sum);
        }
    }

    private boolean cabeEstadiasRuta(List<Arista> edges, List<Long> deps, int qty,
                                     Map<Long, Integer> blockAirport) {
        for (int i = 0; i < edges.size(); i++) {
            Arista e = edges.get(i);
            long llegada = deps.get(i) + e.durationMinutes;
            long salida = (i < edges.size() - 1) ? deps.get(i + 1) : llegada + DEST_STORAGE_MIN;
            if (!cabeAlmacenPierna(e.to, llegada, salida, qty, blockAirport)) return false;
        }
        return true;
    }

    private boolean cabeAlmacenPierna(Nodo node, long llegada, long salida, int qty,
                                      Map<Long, Integer> blockAirport) {
        if (node == null || node.idx < 0 || node.capacity <= 0) return true;
        long s1 = ultimoSlot(llegada, salida);
        for (long s = llegada / STORAGE_SLOT_MIN; s <= s1; s++) {
            long k = slotKey(node.idx, s);
            if (airportOccupancy.getOrDefault(k, 0) + blockAirport.getOrDefault(k, 0)
                    + backlogOrigenOcc.getOrDefault(k, 0) + qty > node.capacity) {
                return false;
            }
        }
        return true;
    }

    private static long toEpochMin(LocalDateTime dt) {
        return dt.toLocalDate().toEpochDay() * DAY_MIN + dt.getHour() * 60L + dt.getMinute();
    }

    private boolean hasAirportCapacity(Nodo node,
                                       boolean destinoFinal,
                                       long arrMin,
                                       int qty,
                                       Map<Long, Integer> blockAirport) {
        if (node == null || node.idx < 0 || node.capacity <= 0) return true;
        long ak = airportKey(node.idx, arrMin);
        return airportOccupancy.getOrDefault(ak, 0) + blockAirport.getOrDefault(ak, 0)
                + backlogOrigenOcc.getOrDefault(ak, 0) + qty <= node.capacity;
    }

    private RutaCandidata toRouteCandidate(EstadoRuta state,
                                            LoteEnvio batch,
                                            long readyMin,
                                            long slaMaxMinutes,
                                            Map<Long, Integer> blockFlight,
                                            Map<Long, Integer> blockAirport) {
        List<Arista> edges = new ArrayList<>();
        List<Long> deps = new ArrayList<>();
        for (EstadoRuta s = state; s.edge != null; s = s.parent) {
            edges.add(0, s.edge);
            deps.add(0, s.depMin);
        }
        return toRouteCandidate(edges, deps, batch, readyMin, slaMaxMinutes, blockFlight, blockAirport);
    }

    private RutaCandidata toRouteCandidate(List<Arista> edges,
                                            List<Long> deps,
                                            LoteEnvio batch,
                                            long readyMin,
                                            long slaMaxMinutes,
                                            Map<Long, Integer> blockFlight,
                                            Map<Long, Integer> blockAirport) {
        if (edges.isEmpty() || deps.size() != edges.size()) return null;

        if (!cabeEstadiasRuta(edges, deps, batch.getQuantity(), blockAirport)) return null;

        long arrivalMin = deps.get(deps.size() - 1) + edges.get(edges.size() - 1).durationMinutes;
        long transitMin = (arrivalMin + DEST_STORAGE_MIN) - readyMin;
        long slackMin = slaMaxMinutes - transitMin;
        double pressure = projectedPressure(edges, deps, batch, blockFlight, blockAirport);
        double scarcity = projectedScarcity(edges, deps, blockFlight, blockAirport);
        return new RutaCandidata(edges, deps, transitMin <= slaMaxMinutes,
                arrivalMin, transitMin, slackMin, pressure, scarcity);
    }

    static double precioCongestion(int usado, int capacidad) {
        if (capacidad <= 0) return 0.0;
        double u = (double) usado / capacidad;
        if (u <= 0.0) return 0.0;
        if (u >= 1.0) return 1000.0;
        return (u * u * u) / Math.max(0.02, 1.0 - u);   // â‰ˆ0 hasta ~0.6, explota cerca de 1
    }

    double precioCongestionAlmacenHub(int usado, int capacidad) {
        if (capacidad <= 0) return 0.0;
        double u = (double) usado / capacidad;
        if (u <= 0.0) return 0.0;
        if (u >= 1.0) return 1000.0;
        return Math.pow(u, precioHubExponente) / Math.max(0.05, 1.0 - u);
    }

    private double projectedScarcity(List<Arista> edges,
                                     List<Long> deps,
                                     Map<Long, Integer> blockFlight,
                                     Map<Long, Integer> blockAirport) {
        double sum = 0.0;
        for (int i = 0; i < edges.size(); i++) {
            Arista e = edges.get(i);
            long depMin = deps.get(i);
            if (e.capacity > 0) {
                int remaining = remainingFlight(e, depMin, blockFlight);
                sum += precioCongestion(e.capacity - remaining, e.capacity);
            }
            if (e.to != null && e.to.idx >= 0 && e.to.capacity > 0) {
                long arrMin = depMin + e.durationMinutes;
                boolean transito = i < edges.size() - 1;   // escala (no destino final)
                // L1: en una escala de HUB el almacÃ©n es el cuello â†’ curva que muerde antes.
                // El destino final (delivery) y las escalas no-hub conservan la curva base.
                boolean hubTransito = transito && esHub(e.to.idx);
                int remaining = capacidadAlmacen(e.to, arrMin, blockAirport);
                int usadoArr = e.to.capacity - remaining;
                sum += hubTransito
                        ? precioCongestionAlmacenHub(usadoArr, e.to.capacity)
                        : precioCongestion(usadoArr, e.to.capacity);
                if (transito) {
                    // Fase R â€” muestrea el slot de fin de estadÃ­a (salida del siguiente vuelo).
                    int remNext = capacidadAlmacen(e.to, deps.get(i + 1), blockAirport);
                    int usadoNext = e.to.capacity - remNext;
                    sum += hubTransito
                            ? precioCongestionAlmacenHub(usadoNext, e.to.capacity)
                            : precioCongestion(usadoNext, e.to.capacity);
                }
            }
        }
        return sum;
    }

    private double projectedStepPressure(Arista edge,
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
                // Fase R â€” proxy de presencia continua: muestrea el slot siguiente al de llegada.
                int remainingNextDay = capacidadAlmacen(edge.to, arrMin + STORAGE_SLOT_MIN, blockAirport);
                max = Math.max(max, (double) (edge.to.capacity - remainingNextDay + qty) / edge.to.capacity);
            }
        }
        return max;
    }

    private double projectedPressure(List<Arista> edges,
                                     List<Long> deps,
                                     LoteEnvio batch,
                                     Map<Long, Integer> blockFlight,
                                     Map<Long, Integer> blockAirport) {
        double max = 0.0;
        int qty = batch.getQuantity();
        for (int i = 0; i < edges.size(); i++) {
            Arista e = edges.get(i);
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
                    // Fase R â€” muestrea el slot de fin de estadÃ­a (salida del siguiente vuelo).
                    int remainingNextDay = capacidadAlmacen(e.to, deps.get(i + 1), blockAirport);
                    max = Math.max(max, (double) (e.to.capacity - remainingNextDay + qty) / e.to.capacity);
                }
            }
        }
        return max;
    }

    private boolean containsNode(EstadoRuta state, int nodeIdx) {
        for (EstadoRuta s = state; s != null; s = s.parent) {
            if (s.nodeIdx == nodeIdx) return true;
        }
        return false;
    }

    private static boolean isDominated(List<EtiquetaRuta> labels, EtiquetaRuta candidate) {
        if (labels == null || labels.isEmpty()) return false;
        for (EtiquetaRuta label : labels) {
            if (label.arrivalMin <= candidate.arrivalMin
                    && label.legs <= candidate.legs
                    && label.pressure <= candidate.pressure) {
                return true;
            }
        }
        return false;
    }

    private static void addLabel(List<EtiquetaRuta>[] labels,
                                 int cell,
                                 EtiquetaRuta candidate,
                                 int maxLabels) {
        List<EtiquetaRuta> bucket = labels[cell];
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
            EtiquetaRuta worst = bucket.get(worstIdx);
            if (compareLabel(candidate, worst) >= 0) return;
            bucket.remove(worstIdx);
        }
        bucket.add(candidate);
    }

    private static int compareLabel(EtiquetaRuta a, EtiquetaRuta b) {
        int c = Long.compare(a.arrivalMin, b.arrivalMin);
        if (c != 0) return c;
        c = Integer.compare(a.legs, b.legs);
        if (c != 0) return c;
        return Double.compare(a.pressure, b.pressure);
    }

    private static int compareRouteCandidates(RutaCandidata a, RutaCandidata b) {
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
    // DiagnÃ³stico
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
        log.info("  Flight-days con ocupaciÃ³n   : {}", flightDaysUsados);
        log.info("  Flight-days al 100 %         : {}", flightDaysLlenos);
        log.info("  Flight-days sobre capacidad  : {}", flightDaysSobre);
        if (totalCapacidad > 0)
            log.info("  UtilizaciÃ³n global           : {}/{} ({} %)",
                    totalAsignado, totalCapacidad, totalAsignado * 100 / totalCapacidad);
        if (!sobre.isEmpty()) {
            log.warn("  Ejemplos sobre capacidad (race condition en paralelo):");
            sobre.stream().limit(5).forEach(s -> log.warn("    {}", s));
        }

        log.info("--- Capacidad de aeropuertos (almacÃ©n) ---");
        long airportDaysUsados = airportOccupancy.size();
        long airportDaysLlenos = 0, airportDaysSobre = 0, totalAirportAsig = 0, totalAirportCap = 0;
        Map<String, long[]> porAero = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : airportOccupancy.entrySet()) {
            int    nodeIdx  = (int)(entry.getKey() >> DAY_BITS);
            int    asignado = entry.getValue();
            totalAirportAsig += asignado;
            String code = (nodeIdx < nodeByIdx.length) ? nodeByIdx[nodeIdx] : "?";
            Nodo nodo   = graph.nodes.get(code);
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
        // Fase R â€” claves por SLOT de tiempo: estos contadores miden PICO CONCURRENTE, no suma diaria.
        log.info("  Airport-slots con ocupaciÃ³n : {}", airportDaysUsados);
        log.info("  Airport-slots al 100 %       : {}", airportDaysLlenos);
        log.info("  Airport-slots sobre capacidad: {}", airportDaysSobre);
        if (totalAirportCap > 0)
            log.info("  UtilizaciÃ³n global aerop.    : {}/{} ({} %)",
                    totalAirportAsig, totalAirportCap, totalAirportAsig * 100 / totalAirportCap);
        log.info("  Top aeropuertos por ocupaciÃ³n total:");
        porAero.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(10)
                .forEach(e -> {
                    long asig = e.getValue()[0], cap = e.getValue()[1];
                    log.info("    {} | total_transit={}{}", e.getKey(), asig,
                            cap > 0 ? " (cap/dÃ­a=" + cap + ")" : "");
                });
    }

    // -----------------------------------------------------------------------
    // Clases internas
    // -----------------------------------------------------------------------

    private static class EstadoRuta {
        final int        nodeIdx;
        final long       arrivalMin;
        final long       depMin;
        final Arista       edge;
        final EstadoRuta parent;
        final int        legs;
        final double     pressure;

        EstadoRuta(int nodeIdx, long arrivalMin, long depMin, Arista edge, EstadoRuta parent) {
            this(nodeIdx, arrivalMin, depMin, edge, parent, parent == null ? 0 : parent.legs + 1,
                    parent == null ? 0.0 : parent.pressure);
        }

        EstadoRuta(int nodeIdx, long arrivalMin, long depMin, Arista edge, EstadoRuta parent, int legs) {
            this(nodeIdx, arrivalMin, depMin, edge, parent, legs, parent == null ? 0.0 : parent.pressure);
        }

        EstadoRuta(int nodeIdx, long arrivalMin, long depMin, Arista edge, EstadoRuta parent, int legs, double pressure) {
            this.nodeIdx    = nodeIdx;
            this.arrivalMin = arrivalMin;
            this.depMin     = depMin;
            this.edge       = edge;
            this.parent     = parent;
            this.legs       = legs;
            this.pressure   = pressure;
        }
    }

    private static final class EtiquetaRuta {
        final long arrivalMin;
        final int legs;
        final double pressure;

        EtiquetaRuta(long arrivalMin, int legs, double pressure) {
            this.arrivalMin = arrivalMin;
            this.legs = legs;
            this.pressure = pressure;
        }
    }

    public static final class RutaCandidata {
        private final List<Arista> edges;
        private final List<Long> actualDepartures;
        private final boolean cumpleSLA;
        private final long arrivalMin;
        private final long transitMin;
        private final long slackMin;
        private final double pressure;
        private final double scarcityCost;
        private String signatureCache;

        private RutaCandidata(List<Arista> edges,
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

        public List<Arista> getEdges() { return edges; }
        public List<Long> getActualDepartures() { return actualDepartures; }
        public boolean isCumpleSLA() { return cumpleSLA; }
        public long getArrivalMin() { return arrivalMin; }
        public long getTransitMin() { return transitMin; }
        public long getSlackMin() { return slackMin; }
        public double getPressure() { return pressure; }
        public double getScarcityCost() { return scarcityCost; }
        public int getLegs() { return edges.size(); }

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

    private static class ResultadoRuta {
        final List<Arista> edges;
        final List<Long> actualDepartures;
        final boolean    cumpleSLA;

        static final ResultadoRuta EMPTY =
                new ResultadoRuta(Collections.emptyList(), Collections.emptyList(), false);

        ResultadoRuta(List<Arista> edges, List<Long> actualDepartures, boolean cumpleSLA) {
            this.edges            = edges;
            this.actualDepartures = actualDepartures;
            this.cumpleSLA        = cumpleSLA;
        }
    }
}
