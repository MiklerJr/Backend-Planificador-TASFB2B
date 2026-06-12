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
    // Fase R — el almacén es OCUPACIÓN CONCURRENTE (maletas presentes a la vez ≤ capacidad), NO
    // throughput diario. Se discretiza el tiempo en slots de STORAGE_SLOT_MIN: una maleta ocupa
    // los slots de su estadía [llegada, salida) — destino final ≈ DEST_STORAGE_MIN (se retira),
    // escala = hasta la salida de su siguiente vuelo. Menor slot = más exacto, más claves.
    public static final long STORAGE_SLOT_MIN = 60L;
    private static final long MAX_HORIZON_MIN  = 3 * 24 * 60L;
    private static final long DAY_MIN          = FlightKeyEncoder.DAY_MIN;
    private static final int  DAY_BITS         = FlightKeyEncoder.DAY_BITS;
    private static final int  MAX_CANDIDATE_LEGS = 10;
    private static final long SKELETON_BUCKET_MIN = 60L;   // bucket de hora-del-día para la cache cross-bloque
    private static final int  MAX_SKELETONS_POR_CLAVE = 8;   // Fase Q: +sitio para esqueletos hub-avoiding

    // Fase O (hubs 100% dinámicos): NO hay lista hardcodeada de hubs. Un aeropuerto se marca hub
    // cuando su día más cargado alcanza esta fracción de su capacidad de almacén — los hubs salen
    // siempre de los DATOS (utilización real), así el algoritmo no se rompe si cambia el dataset
    // (aeropuertos/demanda distintos). reclasificarHubsPorUtilizacion() corre periódicamente desde
    // commitBlock para cubrir todos los escenarios. El hardcode anterior (13 ICAO fijos) sub-cubría:
    // la auditoría mostró ~45% de los fallos del onset en aeropuertos calientes NO listados.
    private static final int    HUB_RECLASIFICAR_CADA = 10;   // bloques entre reclasificaciones
    // Fase P — perillas storage-aware configurables (planificador.storage-aware vía
    // configurarStorageAware()). Defaults conservadores = comportamiento previo a P.
    private double umbralHubPico      = 0.65;   // O: fracción de cap a la que un nodo pasa a hub
    private double precioHubExponente = 2.0;    // L1/P: exponente p de u^p/(1−u) en el precio de hub
    private boolean[] hubByIdx;          // consulta O(1) en el bucle caliente; arranca vacío
    private int commitsDesdeReclasificar = 0;

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

    // Vuelos cancelados: flightKeys con capacidad efectiva = 0. Mutable y concurrente porque las
    // cancelaciones las ordena el usuario EN VIVO durante la simulación (ver
    // PlanificadorService.aplicarCancelacionesVuelo): el worker del job añade entre bloques y el
    // Dijkstra solo lee.
    private final Set<Long> cancelledFlightDays = ConcurrentHashMap.newKeySet();

    // Fase Origen-B — ocupación del almacén de ORIGEN por los envíos en backlog (sinRuta) que
    // esperan ser enrutados. Mapa por SLOT (igual que airportOccupancy), en UTC. Se reconstruye al
    // final de cada bloque desde el backlog vigente y se suma en TODOS los chequeos de capacidad,
    // de modo que los envíos en espera congestionan el origen en tiempo real. Separado de
    // airportOccupancy (que solo lleva tramos de envíos CON ruta, Fase A) para no doble-contar:
    // un envío está en backlogOrigenOcc (sin ruta) o en airportOccupancy (con ruta), nunca en ambos.
    private final ConcurrentHashMap<Long, Integer> backlogOrigenOcc = new ConcurrentHashMap<>();
    // Reloj de simulación en UTC = mayor readyTime visto. Cota superior de la espera en origen.
    private long relojUtcMin = Long.MIN_VALUE;
    // Tope duro de origen: ids de envíos efectivamente ADMITIDOS en backlogOrigenOcc (caben en su
    // almacén de origen). El primer envío que NO cabe es el COLAPSO logístico de origen (detiene la
    // simulación). Permite que removerEsperaOrigenBacklog reste solo a los admitidos (sin negativos).
    private final Set<String> origenAdmitidos = new HashSet<>();

    // H3: cache de esqueletos de ruta (secuencias de edge-idx) reutilizable ENTRE
    // bloques de una misma simulación. La malla de vuelos se repite a diario, así que
    // un esqueleto hallado para (origen,destino,hora-del-día,SLA) sirve los 200 días;
    // solo se revalida capacidad/cancelaciones al materializar. Evita re-ejecutar
    // Dijkstra para patrones recurrentes. Instancia por simulación (GreedyRepairOperator
    // se crea una vez por escenario) y uso single-thread → HashMap normal es seguro.
    final Map<Long, List<int[]>> rutaSkeletonCache = new HashMap<>();   // package-private para tests
    // Fase Q: claves de esqueleto ya intentadas por el re-seed hub-avoiding (una vez cada una).
    private final Set<Long> reSeeded = new HashSet<>();

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

        // Fase O: sin hubs hardcodeados — arranca vacío y se descubren por datos (commitBlock →
        // reclasificarHubsPorUtilizacion). setHubs() permite una sobre-escritura explícita (tests).
        this.hubByIdx = new boolean[nodeCount];
    }

    /**
     * Define explícitamente qué nodos son hub (sobre-escritura manual; usado por tests o por una
     * lista externa). Con {@code codigos} null/vacío deja el conjunto vacío → 100% dinámico vía
     * {@link #reclasificarHubsPorUtilizacion(double)}.
     */
    public void setHubs(Set<String> codigos) {
        marcarHubs(codigos == null ? Collections.emptySet() : codigos);
    }

    /**
     * Fase P — fija las perillas storage-aware desde la config (planificador.storage-aware).
     * {@code umbralHubPico}: fracción de capacidad a la que un aeropuerto pasa a hub (reclasificación
     * dinámica). {@code precioHubExponente}: exponente p de la curva de precio de almacén-hub
     * {@code u^p/(1−u)} (p<2 muerde antes). Llamado por el servicio tras construir el operador.
     */
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

    /** Fase L — true si el nodo (por idx) es un hub de almacén cuello-de-botella. O(1). */
    private boolean esHub(int nodeIdx) {
        return nodeIdx >= 0 && nodeIdx < hubByIdx.length && hubByIdx[nodeIdx];
    }

    /**
     * Fase O — reclasifica el conjunto de hubs a partir de la ocupación REAL del almacén.
     *
     * <p>Recorre {@link #airportOccupancy} (cada entrada es un nodo-día) y, por nodo, calcula su
     * <b>utilización-pico</b> = {@code max} sobre sus días de {@code ocupación / capacidad}. Marca
     * como hub a todo nodo con pico {@code >= umbralPico} — <b>solo desde los datos</b>, sin lista
     * hardcodeada (robusto ante cambios de dataset). Como el pico es un {@code max} sobre ocupación
     * acumulada, el conjunto crece monótonamente (sin flapping).
     *
     * <p>O(airportOccupancy.size()); se invoca periódicamente desde el servicio (no en el bucle
     * caliente por-batch) → no añade cómputo al presupuesto {@code Ta} de enrutado.
     *
     * @param umbralPico fracción de capacidad a partir de la cual un aeropuerto se considera hub
     */
    public void reclasificarHubsPorUtilizacion(double umbralPico) {
        double[] picoUtil = new double[nodeCount];
        for (Map.Entry<Long, Integer> entry : airportOccupancy.entrySet()) {
            int nodeIdx = (int) (entry.getKey() >> DAY_BITS);
            if (nodeIdx < 0 || nodeIdx >= nodeCount) continue;
            String code = nodeByIdx[nodeIdx];
            Node nodo = code != null ? graph.nodes.get(code) : null;
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

    /**
     * Señales crudas de COLAPSO INMINENTE (pre-colapso) para el bloque actual. El servicio aplica
     * los umbrales y arma el mensaje. Cubre los dos criterios de colapso reales:
     * <ul>
     *   <li><b>Almacén</b>: utilización pico {@code (airportOccupancy+backlogOrigenOcc)/capacidad}
     *       sobre los slots tocados este bloque ({@code blockAirport.keySet()} ⇒ costo acotado).</li>
     *   <li><b>Backlog</b>: holgura SLA mínima {@code ((readyTime+SLA) − ahora)/SLA} entre los
     *       pendientes (ahora = {@link #relojUtcMin}). {@code 1.0} si no hay backlog/relojo.</li>
     * </ul>
     */
    public PreColapso evaluarPreColapso(Map<Long, Integer> blockAirport,
                                        Collection<LuggageBatch> pendientes) {
        double utilMax = 0.0;
        String almacenCritico = null;
        if (blockAirport != null) {
            for (Long key : blockAirport.keySet()) {
                int nodeIdx = (int) (key >> DAY_BITS);
                if (nodeIdx < 0 || nodeIdx >= nodeCount) continue;
                String code = nodeByIdx[nodeIdx];
                Node nodo = code != null ? graph.nodes.get(code) : null;
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
            for (LuggageBatch b : pendientes) {
                if (b == null || b.getReadyTime() == null || b.getSlaLimitHours() <= 0) continue;
                long slaMin = (long) b.getSlaLimitHours() * 60L;
                long restante = (toEpochMin(b.getReadyTime()) + slaMin) - relojUtcMin;
                double ratio = restante / (double) slaMin;       // <0 = ya vencido
                if (ratio < holguraMin) { holguraMin = ratio; envioUrgente = b.getId(); }
            }
        }
        return new PreColapso(utilMax, almacenCritico, holguraMin, envioUrgente);
    }

    /** Señales crudas de pre-colapso de un bloque (utilización de almacén y holgura SLA del backlog). */
    public record PreColapso(double utilAlmacenMax, String almacenCritico,
                             double holguraSlaMin, String envioUrgente) {}

    /**
     * Fase Q1 — esqueleto (secuencia de edge-idx) que ALCANZA el destino on-time EVITANDO el
     * tránsito por hubs. Dijkstra earliest-arrival que (a) salta toda expansión hacia un nodo hub
     * que NO sea el destino (el hub-destino no es evitable) y (b) ignora capacidad → es una
     * PLANTILLA reutilizable (la materialización revalida capacidad/cancelaciones por bloque).
     * Devuelve {@code null} si no existe ruta hub-free on-time (p.ej. 24h: mantiene el hub).
     */
    private int[] esqueletoEvitandoHubs(int startIdx, int targetIdx, long readyMin, int slaHours) {
        if (startIdx < 0 || targetIdx < 0 || startIdx == targetIdx) return null;
        long readyDay = readyMin / DAY_MIN;
        long slaMaxMinutes = (long) slaHours * 60;

        long[] bestTimes = new long[nodeCount * DAY_SLOTS];
        Arrays.fill(bestTimes, Long.MAX_VALUE);
        PriorityQueue<RouteState> pq = new PriorityQueue<>(Comparator.comparingLong(s -> s.arrivalMin));

        long horizonDays = MAX_HORIZON_MIN / DAY_MIN;
        for (long d = 0; d <= horizonDays; d++) {
            long startMin = readyMin + d * DAY_MIN;
            bestTimes[startIdx * DAY_SLOTS + (int) d] = startMin;
            pq.add(new RouteState(startIdx, startMin, -1L, null, null));
        }

        while (!pq.isEmpty()) {
            RouteState current = pq.poll();
            if (current.nodeIdx == targetIdx) {
                long transitMinutes = (current.arrivalMin + DEST_STORAGE_MIN) - readyMin;
                if (transitMinutes > slaMaxMinutes) return null;   // la mejor llegada ya es tardía
                int[] sk = new int[current.legs];
                int i = current.legs - 1;
                for (RouteState s = current; s.edge != null; s = s.parent) sk[i--] = s.edge.idx;
                return sk;
            }
            if (current.legs >= MAX_CANDIDATE_LEGS) continue;
            for (Edge flight : adjByIdx[current.nodeIdx]) {
                int nextIdx = (flight.to == null) ? -1 : flight.to.idx;
                if (nextIdx < 0) continue;
                if (nextIdx != targetIdx && esHub(nextIdx)) continue;   // Q: no transitar por hubs
                long minWait  = (current.edge == null) ? 0L : CONNECTION_MIN;
                long actualDep = nextDepartureMin(flight.depMinuteOfDay, current.arrivalMin + minWait);
                long actualArr = actualDep + flight.durationMinutes;
                long dayOffset = actualArr / DAY_MIN - readyDay;
                if (dayOffset < 0 || dayOffset >= DAY_SLOTS) continue;
                if (actualArr - readyMin > MAX_HORIZON_MIN) continue;
                // capacity-free: NO se chequea vuelo ni almacén (plantilla cross-bloque).
                int cell = nextIdx * DAY_SLOTS + (int) dayOffset;
                if (actualArr < bestTimes[cell]) {
                    bestTimes[cell] = actualArr;
                    pq.add(new RouteState(nextIdx, actualArr, actualDep, flight, current));
                }
            }
        }
        return null;
    }

    /**
     * Fase Q2 — inyecta en {@link #rutaSkeletonCache} esqueletos hub-avoiding para las claves de
     * demanda ya vistas, de forma AMORTIZADA y ACOTADA por tiempo (Ta-safe): procesa hasta
     * {@code maxClaves} claves nuevas o hasta {@code deadlineNs}. Cada clave se intenta una sola vez
     * ({@link #reSeeded}). SOLO AGREGA opciones (jamás quita la ruta rápida-por-hub → urgentes
     * intactos); la selección J1 elegirá la sin-hub para los flexibles bajo congestión, SIN Dijkstra
     * extra en el bucle caliente (el fast-path materializa lo cacheado). El servicio la llama con el
     * tiempo ocioso del bloque, así que no añade wall-clock (no repite la regresión K1).
     */
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
            // Fase R — libera exactamente los slots de estadía que cargó applyToBlock.
            if (!esFinalLeg && e.to.idx >= 0) {
                cargarAlmacenPierna(blockAirport, e.to.idx, arrMin, deps.get(i + 1),
                        -batch.getQuantity());
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                cargarAlmacenPierna(blockAirport, e.to.idx, arrMin, arrMin + DEST_STORAGE_MIN,
                        -batch.getQuantity());
            }
        }
        // Fase Origen — libera la ocupación de origen (espejo de applyToBlock).
        cargarOrigen(blockAirport, batch, route, deps, -1);
    }

    /**
     * Marca un vuelo-día como cancelado (capacidad efectiva = 0). Pensado para órdenes EN VIVO del
     * usuario: a partir de la llamada, el Dijkstra deja de usar ese vuelo ese día y los envíos ya
     * comprometidos se re-enrutan vía backlog. {@code flightKey} se construye con
     * {@link FlightKeyEncoder#flightKey(int, long)}.
     *
     * @return true si el vuelo-día no estaba ya cancelado.
     */
    public boolean addCancelledFlight(long flightKey) {
        return cancelledFlightDays.add(flightKey);
    }

    /** ¿Está cancelado este vuelo-día? */
    public boolean isCancelledFlight(long flightKey) {
        return cancelledFlightDays.contains(flightKey);
    }

    /**
     * ¿La ruta asignada de este batch usa algún vuelo-día cancelado? Identifica rutas
     * físicamente imposibles tras una cancelación en vivo, cuya ocupación global sigue
     * commiteada hasta que el llamador la libere con {@link #releaseFromGlobal}.
     */
    public boolean rutaUsaVueloCancelado(LuggageBatch batch) {
        if (batch == null || cancelledFlightDays.isEmpty()) return false;
        List<Edge> route = batch.getAssignedRoute();
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

    /**
     * Fase Origen-A — carga/libera ({@code signo}=+1/−1) la ocupación del almacén de ORIGEN durante
     * la espera del envío CON ruta antes de su primer vuelo: {@code [readyTime, primerVuelo)}.
     */
    private void cargarOrigen(Map<Long, Integer> mapa, LuggageBatch batch,
                             List<Edge> edges, List<Long> deps, int signo) {
        if (edges == null || edges.isEmpty() || deps == null || deps.isEmpty()) return;
        Node origen = edges.get(0).from;
        if (origen == null || origen.idx < 0 || origen.capacity <= 0) return;
        long desde = toEpochMin(batch.getReadyTime());
        long firstDep = deps.get(0);
        if (firstDep <= desde) return;
        cargarAlmacenPierna(mapa, origen.idx, desde, firstDep, signo * batch.getQuantity());
    }

    /** Fase Origen-A — ¿cabe la espera en origen {@code [readyTime, primerVuelo)}? */
    private boolean cabeOrigen(LuggageBatch batch, List<Edge> edges, List<Long> deps,
                               Map<Long, Integer> blockAirport) {
        if (edges == null || edges.isEmpty() || deps == null || deps.isEmpty()) return true;
        Node origen = edges.get(0).from;
        if (origen == null || origen.idx < 0 || origen.capacity <= 0) return true;
        long desde = toEpochMin(batch.getReadyTime());
        long firstDep = deps.get(0);
        if (firstDep <= desde) return true;
        return cabeAlmacenPierna(origen, desde, firstDep, batch.getQuantity(), blockAirport);
    }

    // -----------------------------------------------------------------------
    // Fase Origen-B — ocupación de origen por el backlog (envíos sinRuta en espera)
    // -----------------------------------------------------------------------

    /**
     * Reconstruye {@link #backlogOrigenOcc} desde cero con los envíos pendientes SIN ruta (esperan
     * en su almacén de origen). Cada uno ocupa {@code [readyTime, relojUTC)} con {@code relojUTC} =
     * mayor readyTime visto. Llamar al FINAL de cada bloque (tras reabastecer el backlog).
     *
     * <p>Tope duro: el almacén de origen NO excede su capacidad. Se admite en ORDEN DE LLEGADA
     * (los que llegaron primero ocupan primero); el PRIMER envío que no cabe es la maleta que
     * "llegó a un origen lleno" ⇒ <b>colapso logístico</b> (lo devuelve este método).
     *
     * @param pendientes  backlog vigente (se ignoran los que ya tienen ruta — esos los cobra Fase A)
     * @param bloqueLote  envíos del bloque actual, para avanzar el reloj UTC
     * @return el primer envío que no cupo en su almacén de origen (colapso), o {@code null} si todos cupieron
     */
    public LuggageBatch reconstruirEsperaOrigenBacklog(Collection<LuggageBatch> pendientes,
                                                       Collection<LuggageBatch> bloqueLote) {
        if (bloqueLote != null) {
            for (LuggageBatch b : bloqueLote) {
                if (b == null || b.getReadyTime() == null) continue;
                relojUtcMin = Math.max(relojUtcMin, toEpochMin(b.getReadyTime()));
            }
        }
        backlogOrigenOcc.clear();
        origenAdmitidos.clear();
        if (pendientes == null || relojUtcMin == Long.MIN_VALUE) return null;

        List<LuggageBatch> orden = new ArrayList<>();
        for (LuggageBatch b : pendientes) {
            if (b == null || b.getReadyTime() == null) continue;
            if (b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty()) continue; // con ruta ⇒ Fase A
            orden.add(b);
        }
        // Orden de llegada: readyTime ascendente (los presentes primero conservan su espacio).
        orden.sort(Comparator.comparingLong(b -> toEpochMin(b.getReadyTime())));
        LuggageBatch desbordado = null;
        for (LuggageBatch b : orden) {
            if (cabeEsperaOrigen(b)) {
                acumularEsperaOrigen(b, +1);
                origenAdmitidos.add(b.getId());
            } else if (desbordado == null) {
                desbordado = b;   // primera maleta que no cabe en su origen ⇒ colapso
            }
        }
        return desbordado;
    }

    /**
     * Quita del backlog la ocupación de espera en origen de un envío que vuelve a intentarse este
     * bloque (deja de "esperar pasivamente"; se evaluará para despacho). Evita que su propia espera
     * bloquee su despacho. Llamar al sacar el envío del backlog, antes de re-rutearlo. Solo resta si
     * el envío estaba ADMITIDO (tope duro): los que no cupieron (colapso) no contribuían.
     */
    public void removerEsperaOrigenBacklog(LuggageBatch batch) {
        if (batch == null) return;
        if (batch.getAssignedRoute() != null && !batch.getAssignedRoute().isEmpty()) return;
        if (origenAdmitidos.remove(batch.getId())) {
            acumularEsperaOrigen(batch, -1);
        }
    }

    /** Tope duro — ¿cabe la espera {@code [readyTime, relojUTC)} de este envío en su almacén de origen? */
    private boolean cabeEsperaOrigen(LuggageBatch batch) {
        Node origen = graph.nodes.get(batch.getOriginCode());
        if (origen == null || origen.idx < 0 || origen.capacity <= 0) return true;
        long desde = toEpochMin(batch.getReadyTime());
        if (relojUtcMin <= desde) return true;
        return cabeAlmacenPierna(origen, desde, relojUtcMin, batch.getQuantity(), Map.of());
    }

    private void acumularEsperaOrigen(LuggageBatch batch, int signo) {
        if (batch == null || batch.getReadyTime() == null || relojUtcMin == Long.MIN_VALUE) return;
        Node origen = graph.nodes.get(batch.getOriginCode());
        if (origen == null || origen.idx < 0 || origen.capacity <= 0) return;
        long desde = toEpochMin(batch.getReadyTime());
        if (relojUtcMin <= desde) return;
        cargarAlmacenPierna(backlogOrigenOcc, origen.idx, desde, relojUtcMin, signo * batch.getQuantity());
    }

    /** Confirma los mapas del bloque en la ocupación global al finalizar el bloque. */
    public void commitBlock(Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        blockFlight.forEach((key, qty) -> {
            if (qty != 0) flightOccupancy.merge(key, qty, Integer::sum);
        });
        blockAirport.forEach((key, qty) -> {
            if (qty != 0) airportOccupancy.merge(key, qty, Integer::sum);
        });
        // Fase O: redescubrir hubs desde la ocupación real cada N bloques (todos los escenarios).
        // Fuera del bucle caliente por-batch → Ta-safe; el conjunto solo crece (sin flapping).
        if (++commitsDesdeReclasificar >= HUB_RECLASIFICAR_CADA) {
            commitsDesdeReclasificar = 0;
            reclasificarHubsPorUtilizacion(umbralHubPico);
        }
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
            // Fase R — libera por slots de estadía (mismo intervalo que cargó applyToBlock).
            if (!esFinalLeg && e.to.idx >= 0) {
                cargarAlmacenPierna(airportOccupancy, e.to.idx, arrMin, deps.get(i + 1),
                        -batch.getQuantity());
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                cargarAlmacenPierna(airportOccupancy, e.to.idx, arrMin, arrMin + DEST_STORAGE_MIN,
                        -batch.getQuantity());
            }
        }
        // Fase Origen — libera la ocupación de origen en la ocupación global (espejo de applyToBlock).
        cargarOrigen(airportOccupancy, batch, route, deps, -1);
    }

    // -----------------------------------------------------------------------
    // Dijkstra earliest-arrival con capacidad global + bloque
    // -----------------------------------------------------------------------

    private RouteResult findShortestPath(LuggageBatch batch,
                                          Map<Long, Integer> blockFlight,
                                          Map<Long, Integer> blockAirport) {
        return findShortestPath(batch, blockFlight, blockAirport, false);
    }

    /**
     * Colapso logístico — ¿este envío quedó {@code sinRuta} porque un ALMACÉN estaba lleno
     * (origen, escala o destino) y no por falta de vuelos/SLA?
     *
     * <p>Devuelve {@code true} si NO existe ruta on-time respetando la capacidad de almacén pero
     * SÍ existe ignorándola (manteniendo capacidad de vuelos, conexión mínima, SLA y horizonte).
     * Usa mapas de bloque vacíos: lee la ocupación GLOBAL vigente (ya commiteada en este bloque),
     * por lo que debe llamarse tras {@code commitBlock}. No muta ocupación.
     */
    public boolean sinRutaPorAlmacenLleno(LuggageBatch batch) {
        if (batch == null) return false;
        RouteResult con = findShortestPath(batch, Map.of(), Map.of(), false);
        if (con.cumpleSLA && !con.edges.isEmpty()) return false;  // sí había ruta on-time → no fue almacén
        RouteResult sin = findShortestPath(batch, Map.of(), Map.of(), true);
        return sin.cumpleSLA && !sin.edges.isEmpty();
    }

    /**
     * @param ignorarAlmacen si true, omite los chequeos de capacidad de ALMACÉN (origen, escala y
     *   destino) manteniendo capacidad de vuelos, conexión mínima, SLA y horizonte. Sirve para
     *   diagnosticar si un envío quedó sinRuta por almacén lleno (colapso) vs. por vuelos/SLA.
     */
    private RouteResult findShortestPath(LuggageBatch batch,
                                          Map<Long, Integer> blockFlight,
                                          Map<Long, Integer> blockAirport,
                                          boolean ignorarAlmacen) {
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
                // Fase R — la expansión solo chequeó el slot de LLEGADA de cada pierna (la
                // salida siguiente aún no existía). Aquí la ruta está completa: validar la
                // estadía entera antes de confirmarla; si no cabe, seguir buscando otra
                // llegada (otro día/celda) en vez de cobrar slots nunca validados.
                if (!ignorarAlmacen
                        && !cabeEstadiasRuta(edges, deps, batch.getQuantity(), blockAirport)) {
                    continue;
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

                // Fase Origen — el primer vuelo solo es viable si la espera en el almacén de
                // origen [readyTime, salida) cabe (ocupación concurrente).
                if (!ignorarAlmacen && current.edge == null
                        && !cabeAlmacenPierna(startNodeObj, readyMin, actualDep,
                                batch.getQuantity(), blockAirport)) continue;

                // Capacidad del vuelo (global + bloque)
                if (remainingFlight(flight, actualDep, blockFlight) < batch.getQuantity()) continue;

                // Capacidad de almacén: todas las maletas ingresan al almacén al aterrizar,
                // sea escala o destino final (enunciado: "Sea que hagan escala o sea que
                // esté en su destino final").
                int nextIdx = flight.to.idx;
                if (nextIdx < 0) continue;
                if (!ignorarAlmacen && flight.to.capacity > 0) {
                    // Fase R — chequeo barato del SLOT de llegada (ocupación concurrente). El
                    // intervalo completo de estadía se valida en la materialización.
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
                // Fase Origen — primer vuelo viable solo si la espera en almacén de origen cabe.
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

    /**
     * Fase T (N3) — pre-calienta {@link #rutaSkeletonCache} con las rutas de la demanda de la ventana,
     * UNA sola vez por clave única (origen, destino, hora-del-día, SLA), <b>antes</b> del bucle de bloques.
     * Mueve el costo del Dijkstra <b>fuera</b> del presupuesto Ta: el bucle caliente queda como
     * materialización pura (que revalida capacidad por bloque). No cambia decisiones (mismas rutas).
     *
     * @return número de claves distintas intentadas (Dijkstra ejecutado una vez por cada una).
     */
    public int precalentarEsqueletos(Iterable<LuggageBatch> batches, int maxCandidatos) {
        if (batches == null || maxCandidatos <= 0) return 0;
        Map<Long, Integer> bf = new HashMap<>();   // mapas de bloque vacíos: generarCandidatosRuta solo los lee
        Map<Long, Integer> ba = new HashMap<>();
        Set<Long> vistas = new HashSet<>();
        int calentadas = 0;
        for (LuggageBatch b : batches) {
            if (b == null || b.getReadyTime() == null) continue;
            Node o = graph.nodes.get(b.getOriginCode());
            Node d = graph.nodes.get(b.getDestCode());
            if (o == null || d == null || o.idx < 0 || d.idx < 0) continue;
            long key = skeletonKey(o.idx, d.idx, toEpochMin(b.getReadyTime()), b.getSlaLimitHours());
            if (!vistas.add(key)) continue;                  // un intento por clave única
            generarCandidatosRuta(b, bf, ba, maxCandidatos); // popula rutaSkeletonCache en el miss
            calentadas++;
        }
        return calentadas;
    }

    /** Clave de la cache de esqueletos: origen, destino, hora-del-día y SLA (independiente del día). */
    static long skeletonKey(int startIdx, int targetIdx, long readyMin, int slaHours) {   // package-private para tests
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
            // Fase R — carga por OCUPACIÓN concurrente: cada slot de la estadía real de la pierna.
            if (!esFinalLeg && e.to.idx >= 0) {
                long salida = result.actualDepartures.get(i + 1);   // hasta que sale el siguiente vuelo
                cargarAlmacenPierna(blockAirport, e.to.idx, arrMin, salida, batch.getQuantity());
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                // Destino final: la maleta se retira ~DEST_STORAGE_MIN tras aterrizar (1 slot).
                cargarAlmacenPierna(blockAirport, e.to.idx, arrMin, arrMin + DEST_STORAGE_MIN,
                        batch.getQuantity());
            }
        }
        // Fase Origen — ocupa el almacén de origen mientras el envío espera su primer vuelo.
        cargarOrigen(blockAirport, batch, result.edges, result.actualDepartures, +1);
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
             - blockAirport.getOrDefault(key, 0)
             - backlogOrigenOcc.getOrDefault(key, 0);
    }

    /**
     * Ocupación global acumulada de un vuelo-día (clave de {@link FlightKeyEncoder#flightKey}).
     * Tras {@link #commitBlock} incluye el bloque recién confirmado: es la carga total con la que
     * la telemetría debe reportar el vuelo-día (no el delta de un bloque).
     */
    public int ocupacionGlobalVuelo(long flightKey) {
        return flightOccupancy.getOrDefault(flightKey, 0);
    }

    /**
     * Ocupación global acumulada de un slot de almacén (clave de {@link #claveAlmacenDeSlot}).
     * Suma las estadías commiteadas y la espera en origen del backlog sin ruta
     * ({@code backlogOrigenOcc}) — los mismos sumandos que el modelo interno resta en
     * {@link #capacidadAlmacen} — para que la telemetría vea la presión real del slot.
     */
    public int ocupacionGlobalAlmacen(long slotKey) {
        return airportOccupancy.getOrDefault(slotKey, 0)
             + backlogOrigenOcc.getOrDefault(slotKey, 0);
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
        return rutaSirveParaBatch(candidate, batch, blockFlight, blockAirport, 0.0, 0.0);
    }

    /** Compat: reserva de vuelos (J4) sin reserva de almacén. */
    public boolean rutaSirveParaBatch(RouteCandidate candidate,
                                      LuggageBatch batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport,
                                      double reservaBase) {
        return rutaSirveParaBatch(candidate, batch, blockFlight, blockAirport, reservaBase, 0.0);
    }

    /**
     * Variante con <b>reserva (J4 + Fase L2)</b>: para un envío flexible exige un colchón de
     * capacidad libre escalado por su holgura en (a) cada VUELO (`reservaBase`, J4) y (b) el
     * ALMACÉN-día de cada escala de HUB en su estadía overnight (`reservaAlmacenBase`, L2 — el
     * recurso cuello verificado). Los envíos urgentes (holgura baja) casi no reservan; el
     * destino final y las escalas no-hub no llevan reserva de almacén. Con ambas bases en 0
     * equivale al chequeo normal. El caller DEBE reintentar con bases=0 si ninguna ruta pasa,
     * para no crear un sinRuta evitable (invariante anti-J3/K1).
     */
    public boolean rutaSirveParaBatch(RouteCandidate candidate,
                                      LuggageBatch batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport,
                                      double reservaBase,
                                      double reservaAlmacenBase) {
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
        if (reservaBase > 0.0 || reservaAlmacenBase > 0.0) {
            double slaMin = Math.max(1.0, batch.getSlaLimitHours() * 60.0);
            slackRatio = Math.max(0.0, Math.min(1.0, candidate.getSlackMin() / slaMin));
        }
        double reservaVuelo = reservaBase * slackRatio;
        double reservaAlmacen = reservaAlmacenBase * slackRatio;

        int qty = batch.getQuantity();
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            long depMin = deps.get(i);
            int colchonVuelo = reservaVuelo > 0.0 && e.capacity > 0
                    ? (int) Math.ceil(reservaVuelo * e.capacity) : 0;
            if (remainingFlight(e, depMin, blockFlight) < qty + colchonVuelo) return false;
            boolean finalLeg = (i == edges.size() - 1);
            long llegada = depMin + e.durationMinutes;
            // Fase R — capacidad CONCURRENTE: la estadía real ocupa [llegada, salida); cada slot
            // del intervalo debe caber. Destino final ≈ DEST_STORAGE_MIN; escala = hasta salir.
            long salida = finalLeg ? llegada + DEST_STORAGE_MIN : deps.get(i + 1);
            if (!cabeAlmacenPierna(e.to, llegada, salida, qty, blockAirport)) {
                return false;
            }
            // L2: colchón en almacén de HUB para la estadía de una ESCALA (no destino final).
            // Protege el storage concurrente de hub para los envíos urgentes/24h.
            if (!finalLeg && reservaAlmacen > 0.0 && e.to != null && e.to.capacity > 0
                    && esHub(e.to.idx)) {
                int colchonAlm = (int) Math.ceil(reservaAlmacen * e.to.capacity);
                if (!cabeAlmacenPierna(e.to, llegada, salida, qty + colchonAlm, blockAirport)) {
                    return false;
                }
            }
        }
        // Fase Origen — la espera en el almacén de origen también debe caber.
        if (!cabeOrigen(batch, edges, deps, blockAirport)) return false;
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
    public Set<Long> clavesOcupadas(RouteCandidate candidate, LuggageBatch batch) {
        if (candidate == null) return Collections.emptySet();
        List<Edge> edges = candidate.getEdges();
        List<Long> deps = candidate.getActualDepartures();
        if (edges.isEmpty() || deps.size() != edges.size()) return Collections.emptySet();

        Set<Long> keys = new HashSet<>(edges.size() * 3);
        // Fase Origen — incluye los slots de espera en el almacén de origen.
        if (batch != null) {
            Node origen = edges.get(0).from;
            if (origen != null && origen.idx >= 0 && origen.capacity > 0) {
                long desde = toEpochMin(batch.getReadyTime());
                long firstDep = deps.get(0);
                if (firstDep > desde) agregarSlotsEstadia(keys, origen.idx, desde, firstDep);
            }
        }
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.durationMinutes;

            keys.add(flightKey(e.idx, depMin));

            boolean esFinalLeg = (i == edges.size() - 1);
            // Fase R — claves por SLOT de la estadía concurrente (mismo intervalo que applyToBlock).
            if (!esFinalLeg && e.to.idx >= 0) {
                agregarSlotsEstadia(keys, e.to.idx, arrMin, deps.get(i + 1));
            } else if (esFinalLeg && e.to.idx >= 0 && e.to.capacity > 0) {
                agregarSlotsEstadia(keys, e.to.idx, arrMin, arrMin + DEST_STORAGE_MIN);
            }
        }
        return keys;
    }

    private static long flightKey(int edgeIdx, long epochMin) {
        return FlightKeyEncoder.flightKey(edgeIdx, epochMin);
    }

    // Fase R — clave de almacén por SLOT de tiempo (no por día): el instante t cae en el slot
    // t/STORAGE_SLOT_MIN, así la ocupación es CONCURRENTE (maletas presentes a la vez), no diaria.
    private static long airportKey(int nodeIdx, long epochMin) {
        return slotKey(nodeIdx, epochMin / STORAGE_SLOT_MIN);
    }

    /** Clave (nodo, slot) para la ocupación de almacén concurrente. */
    private static long slotKey(int nodeIdx, long slot) {
        return (((long) nodeIdx) << DAY_BITS) | (slot & FlightKeyEncoder.DAY_MASK);
    }

    /**
     * Fase R — clave de almacén (slot de tiempo) para un instante {@code epochMin}. Pública para
     * que los tests siembren {@code airportOccupancy}/{@code blockAirport} en la MISMA franja que
     * usa la contabilidad interna (sustituye a {@code FlightKeyEncoder.airportKey}, que era diaria).
     */
    public static long claveAlmacenDeSlot(int nodeIdx, long epochMin) {
        return airportKey(nodeIdx, epochMin);
    }

    /** Último slot que contiene un instante < {@code salida} (salida exclusiva). */
    private static long ultimoSlot(long llegada, long salida) {
        return (Math.max(llegada + 1, salida) - 1) / STORAGE_SLOT_MIN;
    }

    /** Añade a {@code keys} todas las claves de slot de la estadía {@code [llegada, salida)}. */
    private void agregarSlotsEstadia(Set<Long> keys, int nodeIdx, long llegada, long salida) {
        if (nodeIdx < 0) return;
        long s1 = ultimoSlot(llegada, salida);
        for (long s = llegada / STORAGE_SLOT_MIN; s <= s1; s++) {
            keys.add(slotKey(nodeIdx, s));
        }
    }

    /**
     * Fase R — suma {@code delta} (carga +qty / libera −qty) a cada slot de almacén que la estadía
     * {@code [llegada, salida)} de una pierna ocupa en {@code mapa}. Destino final: salida ≈
     * llegada+DEST_STORAGE_MIN (1 slot). Escala: salida = depMin del siguiente vuelo.
     */
    private void cargarAlmacenPierna(Map<Long, Integer> mapa, int nodeIdx,
                                     long llegada, long salida, int delta) {
        if (nodeIdx < 0) return;
        long s1 = ultimoSlot(llegada, salida);
        for (long s = llegada / STORAGE_SLOT_MIN; s <= s1; s++) {
            mapa.merge(slotKey(nodeIdx, s), delta, Integer::sum);
        }
    }

    /**
     * Fase R — valida la estadía COMPLETA de cada pierna de una ruta ya resuelta (todas las
     * salidas conocidas): escala = {@code [llegada, salida del siguiente vuelo)}; destino final =
     * {@code [llegada, llegada+DEST_STORAGE_MIN)}. Es la contraparte exacta de lo que cobra
     * {@link #applyToBlock}: sin esta pasada, los chequeos por slot-de-llegada de la expansión y
     * la materialización dejan sin validar los slots intermedios de la estadía (overflow).
     */
    private boolean cabeEstadiasRuta(List<Edge> edges, List<Long> deps, int qty,
                                     Map<Long, Integer> blockAirport) {
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            long llegada = deps.get(i) + e.durationMinutes;
            long salida = (i < edges.size() - 1) ? deps.get(i + 1) : llegada + DEST_STORAGE_MIN;
            if (!cabeAlmacenPierna(e.to, llegada, salida, qty, blockAirport)) return false;
        }
        return true;
    }

    /**
     * Fase R — true si caben {@code qty} maletas en TODOS los slots de la estadía
     * {@code [llegada, salida)} de la pierna (ocupación concurrente global + bloque ≤ capacidad).
     */
    private boolean cabeAlmacenPierna(Node node, long llegada, long salida, int qty,
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

    /**
     * Fase R — chequeo barato del SLOT de llegada para la expansión del Dijkstra (capacidad
     * concurrente). El intervalo completo de estadía se valida en la materialización
     * ({@link #rutaSirveParaBatch}/{@link #cabeAlmacenPierna}), que ya conoce la salida siguiente.
     * El parámetro {@code destinoFinal} se conserva por compatibilidad de firma pero ya no altera
     * el chequeo: el bloqueo overnight por día (+DAY_MIN) era propio del modelo de cubo-diario.
     */
    private boolean hasAirportCapacity(Node node,
                                       boolean destinoFinal,
                                       long arrMin,
                                       int qty,
                                       Map<Long, Integer> blockAirport) {
        if (node == null || node.idx < 0 || node.capacity <= 0) return true;
        long ak = airportKey(node.idx, arrMin);
        return airportOccupancy.getOrDefault(ak, 0) + blockAirport.getOrDefault(ak, 0)
                + backlogOrigenOcc.getOrDefault(ak, 0) + qty <= node.capacity;
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

        // Fase R — todo candidato (Dijkstra hijo, cache H3, materialización) pasa por aquí con
        // las salidas ya resueltas: validar la estadía COMPLETA de cada pierna. Los chequeos
        // previos solo cubrían el slot de llegada, y las hormigas del ACO aplican el candidato
        // al bloque sin pasar por rutaSirveParaBatch — sin esto, los slots intermedios de una
        // escala (p. ej. overnight) se cobraban sin haberse validado.
        if (!cabeEstadiasRuta(edges, deps, batch.getQuantity(), blockAirport)) return null;

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

    /**
     * Fase L1/P — precio de congestión para ALMACÉN-día de HUB: {@code u^p/(1−u)} con exponente
     * {@code p = precioHubExponente} configurable (Fase P). p<2 muerde antes (p=2 ⇒ desde ~0.45;
     * p=1.7 ⇒ desde ~0.35) porque la saturación de almacén de hub es local/temporal (celdas-día al
     * 100% mientras el promedio global sigue <60%). Así una ruta que apila tránsito overnight en un
     * hub cuesta notablemente más, y los envíos FLEXIBLES (escalado por holgura en
     * {@code costoSeleccion}) la evitan desviándose a aeropuertos no-hub; los urgentes la siguen tomando.
     */
    double precioCongestionAlmacenHub(int usado, int capacidad) {
        if (capacidad <= 0) return 0.0;
        double u = (double) usado / capacidad;
        if (u <= 0.0) return 0.0;
        if (u >= 1.0) return 1000.0;
        return Math.pow(u, precioHubExponente) / Math.max(0.05, 1.0 - u);
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
                boolean transito = i < edges.size() - 1;   // escala (no destino final)
                // L1: en una escala de HUB el almacén es el cuello → curva que muerde antes.
                // El destino final (delivery) y las escalas no-hub conservan la curva base.
                boolean hubTransito = transito && esHub(e.to.idx);
                int remaining = capacidadAlmacen(e.to, arrMin, blockAirport);
                int usadoArr = e.to.capacity - remaining;
                sum += hubTransito
                        ? precioCongestionAlmacenHub(usadoArr, e.to.capacity)
                        : precioCongestion(usadoArr, e.to.capacity);
                if (transito) {
                    // Fase R — muestrea el slot de fin de estadía (salida del siguiente vuelo).
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
                // Fase R — proxy de presencia continua: muestrea el slot siguiente al de llegada.
                int remainingNextDay = capacidadAlmacen(edge.to, arrMin + STORAGE_SLOT_MIN, blockAirport);
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
                    // Fase R — muestrea el slot de fin de estadía (salida del siguiente vuelo).
                    int remainingNextDay = capacidadAlmacen(e.to, deps.get(i + 1), blockAirport);
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
        // Fase R — claves por SLOT de tiempo: estos contadores miden PICO CONCURRENTE, no suma diaria.
        log.info("  Airport-slots con ocupación : {}", airportDaysUsados);
        log.info("  Airport-slots al 100 %       : {}", airportDaysLlenos);
        log.info("  Airport-slots sobre capacidad: {}", airportDaysSobre);
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
