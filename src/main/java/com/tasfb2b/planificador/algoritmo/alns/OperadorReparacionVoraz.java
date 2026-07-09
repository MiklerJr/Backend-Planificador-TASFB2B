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

    // Escala mínima (permanencia mín. en un aeropuerto entre vuelos), en minutos. Configurable por yaml
    // (planificador.operativo.tiempo-min-escala-minutos) vía configurarTiempoMinEscala; default 10.
    private long conexionMin = 10L;
    // Tiempo de espera en el destino final hasta el recojo (min). Configurable por yaml
    // (planificador.operativo.tiempo-recojo-destino-minutos) vía configurarTiempoRecojoDestino; default 15.
    private long tiempoRecojoDestino = 15L;
    public static final long SLOT_ALMACEN_MIN = 60L;
    private static final long HORIZONTE_MAX_MIN  = 3 * 24 * 60L;
    private static final long MIN_DIA          = CodificadorClaveVuelo.MIN_DIA;
    private static final int  BITS_DIA         = CodificadorClaveVuelo.BITS_DIA;
    private static final int  MAX_TRAMOS_CANDIDATO = 10;
    private static final long BUCKET_ESQUELETO_MIN = 60L;   // bucket de hora-del-dÃ­a para la cache cross-bloque
    private static final int  MAX_ESQUELETOS_POR_CLAVE = 8;   // sitio para esqueletos hub-avoiding
    private static final int    HUB_RECLASIFICAR_CADA = 10;   // bloques entre reclasificaciones
    private double umbralHubPico      = 0.65;   // fracciÃ³n de cap a la que un nodo pasa a hub
    private double precioHubExponente = 2.0;    // exponente p de u^p/(1âˆ’u) en el precio de hub
    private boolean[] hubPorIndice;          // consulta O(1) en el bucle caliente; arranca vacÃ­o
    private int confirmacionesDesdeReclasificar = 0;

    private final Grafo grafo;

    // NO finales: las altas EN CALIENTE (incorporarArista/incorporarNodo) los reemplazan por
    // copias ampliadas (append-only: ningún índice existente se mueve).
    private int                  conteoNodos;
    private static final int SLOTS_DIA = (int)(HORIZONTE_MAX_MIN / MIN_DIA) + 1; // 4

    private Arista[]             aristaPorIndice;
    private String[]             nodoPorIndice;
    private List<Arista>[]       adyacenciaPorIndice;   // adyacenciaPorIndice[node.indice] â†’ vecinos salientes

    private final ConcurrentHashMap<Long, Integer> ocupacionVuelo  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> ocupacionAeropuerto = new ConcurrentHashMap<>();

    private final Set<Long> vueloDiasCancelados = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<Long, Integer> ocupacionOrigenBacklog = new ConcurrentHashMap<>();
    private long relojUtcMin = Long.MIN_VALUE;
    private final Set<String> origenAdmitidos = new HashSet<>();

    final Map<Long, List<int[]>> rutaCacheEsqueleto;   // package-private para tests; se asigna en el constructor
    private final Set<Long> reSeeded = new HashSet<>();

    public OperadorReparacionVoraz(Grafo grafo) {
        this(grafo, new HashMap<>());
    }

    public OperadorReparacionVoraz(Grafo grafo, Map<Long, List<int[]>> rutaCacheEsqueleto) {
        this.rutaCacheEsqueleto = rutaCacheEsqueleto;
        this.grafo = grafo;

        // Asignar idx entero a cada nodo, en orden lexicogrÃ¡fico de ICAO (TreeMap): determinista y
        // estable entre corridas/reinicios aunque el mapa de nodos cambie de implementaciÃ³n o las
        // altas EN CALIENTE agreguen/quiten nodos (siempre revertidas antes de construir el operador).
        // Sostiene las claves de la cachÃ© de esqueletos persistida (skeletonKey empaqueta Ã­ndices).
        Map<String, Integer> nodeIndex = new HashMap<>(grafo.nodos.size() * 2);
        int i = 0;
        for (Map.Entry<String, Nodo> entry : new TreeMap<>(grafo.nodos).entrySet()) {
            nodeIndex.put(entry.getKey(), i);
            entry.getValue().indice = i;
            i++;
        }
        conteoNodos = i;

        nodoPorIndice = new String[conteoNodos];
        for (Map.Entry<String, Integer> e : nodeIndex.entrySet()) nodoPorIndice[e.getValue()] = e.getKey();

        int maxIdx = -1;
        for (Arista e : grafo.aristas) if (e.indice > maxIdx) maxIdx = e.indice;
        aristaPorIndice = new Arista[maxIdx + 1];
        for (Arista e : grafo.aristas) aristaPorIndice[e.indice] = e;

        // Lista de adyacencia indexada por node.indice (evita HashMap lookup en inner loop)
        @SuppressWarnings("unchecked")
        List<Arista>[] adj = new List[conteoNodos];
        for (int j = 0; j < conteoNodos; j++) adj[j] = new ArrayList<>();
        for (Arista e : grafo.aristas) {
            if (e.origen != null && e.origen.indice >= 0) adj[e.origen.indice].add(e);
        }
        adyacenciaPorIndice = adj;

        this.hubPorIndice = new boolean[conteoNodos];
    }

    /**
     * Alta EN CALIENTE de un vuelo (frontera de bloque, hilo worker): incorpora una arista nueva a las
     * estructuras congeladas en el constructor. Solo acepta append-only ({@code indice} nuevo, mayor que
     * todos los existentes): así ningún índice existente se mueve y las claves de ocupación/esqueletos
     * acumuladas siguen válidas. La ocupación es un mapa disperso, no requiere redimensionar.
     *
     * @return true si se incorporó; false si el índice no es append-only o el origen es desconocido.
     */
    public boolean incorporarArista(Arista e) {
        if (e == null || e.indice < aristaPorIndice.length) return false;   // solo append-only
        if (e.origen == null || e.origen.indice < 0 || e.origen.indice >= adyacenciaPorIndice.length) {
            return false;   // origen fuera del snapshot (nodo nuevo sin incorporar)
        }
        Arista[] ampliado = Arrays.copyOf(aristaPorIndice, e.indice + 1);
        ampliado[e.indice] = e;
        aristaPorIndice = ampliado;
        adyacenciaPorIndice[e.origen.indice].add(e);
        return true;
    }

    /**
     * Alta EN CALIENTE de un aeropuerto (frontera de bloque, hilo worker): incorpora un nodo nuevo
     * al final de las estructuras congeladas en el constructor (append-only: los índices existentes
     * no se mueven). Las búsquedas dimensionan sus arrays por {@code conteoNodos} en cada llamada,
     * así que el nodo participa desde la siguiente búsqueda sin más cambios.
     *
     * @return el índice asignado al nodo (o el que ya tenía si ya estaba incorporado).
     */
    public int incorporarNodo(Nodo nodo) {
        if (nodo == null) return -1;
        if (nodo.indice >= 0 && nodo.indice < conteoNodos) return nodo.indice;   // ya incorporado
        int idx = conteoNodos;
        nodo.indice = idx;
        nodoPorIndice = Arrays.copyOf(nodoPorIndice, idx + 1);
        nodoPorIndice[idx] = nodo.codigo;
        adyacenciaPorIndice = Arrays.copyOf(adyacenciaPorIndice, idx + 1);
        adyacenciaPorIndice[idx] = new ArrayList<>();
        hubPorIndice = Arrays.copyOf(hubPorIndice, idx + 1);
        conteoNodos = idx + 1;
        return idx;
    }

    public void setHubs(Set<String> codigos) {
        marcarHubs(codigos == null ? Collections.emptySet() : codigos);
    }

    public void configurarStorageAware(double umbralHubPico, double precioHubExponente) {
        if (umbralHubPico > 0.0) this.umbralHubPico = umbralHubPico;
        if (precioHubExponente > 0.0) this.precioHubExponente = precioHubExponente;
    }

    /** Escala mínima (permanencia mín. en aeropuerto entre vuelos), en minutos. Se aplica en el ruteo. */
    public void configurarTiempoMinEscala(long minutos) {
        if (minutos >= 0) this.conexionMin = minutos;
    }

    /** Tiempo de espera en el destino final hasta el recojo (min). Cuenta para SLA y ocupación destino. */
    public void configurarTiempoRecojoDestino(long minutos) {
        if (minutos >= 0) this.tiempoRecojoDestino = minutos;
    }

    private void marcarHubs(Set<String> codigos) {
        boolean[] flags = new boolean[conteoNodos];
        for (int idx = 0; idx < conteoNodos; idx++) {
            flags[idx] = nodoPorIndice[idx] != null && codigos.contains(nodoPorIndice[idx]);
        }
        this.hubPorIndice = flags;
    }

    private boolean esHub(int nodeIdx) {
        return nodeIdx >= 0 && nodeIdx < hubPorIndice.length && hubPorIndice[nodeIdx];
    }

    public void reclasificarHubsPorUtilizacion(double umbralPico) {
        double[] picoUtil = new double[conteoNodos];
        for (Map.Entry<Long, Integer> entry : ocupacionAeropuerto.entrySet()) {
            int nodeIdx = (int) (entry.getKey() >> BITS_DIA);
            if (nodeIdx < 0 || nodeIdx >= conteoNodos) continue;
            String code = nodoPorIndice[nodeIdx];
            Nodo nodo = code != null ? grafo.nodos.get(code) : null;
            if (nodo == null || nodo.capacidad <= 0) continue;
            double util = entry.getValue() / (double) nodo.capacidad;
            if (util > picoUtil[nodeIdx]) picoUtil[nodeIdx] = util;
        }

        boolean[] flags = new boolean[conteoNodos];
        for (int idx = 0; idx < conteoNodos; idx++) {
            flags[idx] = picoUtil[idx] >= umbralPico;
        }
        this.hubPorIndice = flags;
    }

    public PreColapso evaluarPreColapso(Map<Long, Integer> blockAirport,
                                        Collection<LoteEnvio> pendientes) {
        double utilMax = 0.0;
        String almacenCritico = null;
        if (blockAirport != null) {
            for (Long key : blockAirport.keySet()) {
                int nodeIdx = (int) (key >> BITS_DIA);
                if (nodeIdx < 0 || nodeIdx >= conteoNodos) continue;
                String code = nodoPorIndice[nodeIdx];
                Nodo nodo = code != null ? grafo.nodos.get(code) : null;
                if (nodo == null || nodo.capacidad <= 0) continue;
                int ocupado = ocupacionAeropuerto.getOrDefault(key, 0)
                        + ocupacionOrigenBacklog.getOrDefault(key, 0);
                double util = ocupado / (double) nodo.capacidad;
                if (util > utilMax) { utilMax = util; almacenCritico = code; }
            }
        }

        double holguraMin = 1.0;
        String envioUrgente = null;
        if (pendientes != null && relojUtcMin != Long.MIN_VALUE) {
            for (LoteEnvio b : pendientes) {
                if (b == null || b.getTiempoListo() == null || b.getHorasLimiteSla() <= 0) continue;
                long slaMin = (long) b.getHorasLimiteSla() * 60L;
                long restante = (aMinutoEpoch(b.getTiempoListo()) + slaMin) - relojUtcMin;
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
        long readyDay = readyMin / MIN_DIA;
        long slaMaxMinutes = (long) slaHours * 60;

        long[] bestTimes = new long[conteoNodos * SLOTS_DIA];
        Arrays.fill(bestTimes, Long.MAX_VALUE);
        PriorityQueue<EstadoRuta> pq = new PriorityQueue<>(Comparator.comparingLong(s -> s.arrivalMin));

        long horizonDays = HORIZONTE_MAX_MIN / MIN_DIA;
        for (long d = 0; d <= horizonDays; d++) {
            long startMin = readyMin + d * MIN_DIA;
            bestTimes[startIdx * SLOTS_DIA + (int) d] = startMin;
            pq.add(new EstadoRuta(startIdx, startMin, -1L, null, null));
        }

        while (!pq.isEmpty()) {
            EstadoRuta current = pq.poll();
            if (current.nodeIdx == targetIdx) {
                long transitMinutes = (current.arrivalMin + tiempoRecojoDestino) - readyMin;
                if (transitMinutes > slaMaxMinutes) return null;   // la mejor llegada ya es tardÃ­a
                int[] sk = new int[current.legs];
                int i = current.legs - 1;
                for (EstadoRuta s = current; s.edge != null; s = s.parent) sk[i--] = s.edge.indice;
                return sk;
            }
            if (current.legs >= MAX_TRAMOS_CANDIDATO) continue;
            for (Arista flight : adyacenciaPorIndice[current.nodeIdx]) {
                int nextIdx = (flight.destino == null) ? -1 : flight.destino.indice;
                if (nextIdx < 0) continue;
                if (nextIdx != targetIdx && esHub(nextIdx)) continue;   // no transitar por hubs
                long minWait  = (current.edge == null) ? 0L : conexionMin;
                long actualDep = proximaSalidaMin(flight.minutoDelDiaSalida, current.arrivalMin + minWait);
                long actualArr = actualDep + flight.duracionMinutos;
                long dayOffset = actualArr / MIN_DIA - readyDay;
                if (dayOffset < 0 || dayOffset >= SLOTS_DIA) continue;
                if (actualArr - readyMin > HORIZONTE_MAX_MIN) continue;
                // capacity-free: NO se chequea vuelo ni almacÃ©n (plantilla cross-bloque).
                int cell = nextIdx * SLOTS_DIA + (int) dayOffset;
                if (actualArr < bestTimes[cell]) {
                    bestTimes[cell] = actualArr;
                    pq.add(new EstadoRuta(nextIdx, actualArr, actualDep, flight, current));
                }
            }
        }
        return null;
    }

    public void reSeedHubAvoiding(int maxClaves, long deadlineNs) {
        if (maxClaves <= 0 || rutaCacheEsqueleto.isEmpty()) return;
        int procesadas = 0;
        for (Map.Entry<Long, List<int[]>> e : rutaCacheEsqueleto.entrySet()) {
            if (procesadas >= maxClaves || System.nanoTime() >= deadlineNs) break;
            long key = e.getKey();
            if (!reSeeded.add(key)) continue;        // ya intentada
            procesadas++;
            int startIdx   = (int) (key >>> 40);
            int targetIdx  = (int) ((key >>> 24) & 0xFFFFL);
            int hourBucket = (int) ((key >>> 8) & 0xFFFFL);
            int slaHours   = (int) (key & 0xFFL);
            int[] sk = esqueletoEvitandoHubs(startIdx, targetIdx, hourBucket * BUCKET_ESQUELETO_MIN, slaHours);
            if (sk == null || sk.length == 0) continue;
            List<int[]> lista = e.getValue();
            if (lista == null || lista.size() >= MAX_ESQUELETOS_POR_CLAVE) continue;
            boolean existe = false;
            for (int[] s : lista) if (Arrays.equals(s, sk)) { existe = true; break; }
            if (!existe) lista.add(sk);
        }
    }

    // -----------------------------------------------------------------------
    // Interface OperadorReparacion
    // -----------------------------------------------------------------------

    @Override
    public void reparar(SolucionAlns solution, List<LoteEnvio> unassigned,
                       Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        for (LoteEnvio batch : unassigned) {
            ResultadoRuta result = buscarRutaMasCorta(batch, blockFlight, blockAirport);
            batch.setRutaAsignada(result.aristas);
            batch.setSalidasAsignadas(result.salidasReales);
            batch.setCumpleSLA(result.cumpleSLA);
            aplicarABloque(batch, result, blockFlight, blockAirport);
        }
    }

    // -----------------------------------------------------------------------
    // MÃ©todos de gestiÃ³n de ocupaciÃ³n por bloque
    // -----------------------------------------------------------------------

    public void liberarDeBloque(LoteEnvio batch,
                                  Map<Long, Integer> blockFlight,
                                  Map<Long, Integer> blockAirport) {
        List<Arista> route = batch.getRutaAsignada();
        List<Long> deps  = batch.getSalidasAsignadas();
        if (route == null || route.isEmpty() || deps == null || deps.isEmpty()) return;

        for (int i = 0; i < route.size(); i++) {
            Arista e      = route.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.duracionMinutos;

            blockFlight.merge(claveVuelo(e.indice, depMin), -batch.getCantidad(), Integer::sum);

            boolean esFinalLeg = (i == route.size() - 1);
            // Libera exactamente los slots de estadÃ­a que cargÃ³ aplicarABloque.
            if (!esFinalLeg && e.destino.indice >= 0) {
                cargarAlmacenPierna(blockAirport, e.destino.indice, arrMin, deps.get(i + 1),
                        -batch.getCantidad());
            } else if (esFinalLeg && e.destino.indice >= 0 && e.destino.capacidad > 0) {
                cargarAlmacenPierna(blockAirport, e.destino.indice, arrMin, arrMin + tiempoRecojoDestino,
                        -batch.getCantidad());
            }
        }
        // Libera la ocupaciÃ³n de origen (espejo de aplicarABloque).
        cargarOrigen(blockAirport, batch, route, deps, -1);
    }

    public boolean agregarVueloCancelado(long claveVuelo) {
        return vueloDiasCancelados.add(claveVuelo);
    }

    public boolean esVueloCancelado(long claveVuelo) {
        return vueloDiasCancelados.contains(claveVuelo);
    }

    public boolean rutaUsaVueloCancelado(LoteEnvio batch) {
        if (batch == null || vueloDiasCancelados.isEmpty()) return false;
        List<Arista> route = batch.getRutaAsignada();
        List<Long> deps  = batch.getSalidasAsignadas();
        if (route == null || route.isEmpty() || deps == null || deps.size() != route.size()) {
            return false;
        }
        for (int i = 0; i < route.size(); i++) {
            if (vueloDiasCancelados.contains(claveVuelo(route.get(i).indice, deps.get(i)))) {
                return true;
            }
        }
        return false;
    }

    private void cargarOrigen(Map<Long, Integer> mapa, LoteEnvio batch,
                             List<Arista> edges, List<Long> deps, int signo) {
        if (edges == null || edges.isEmpty() || deps == null || deps.isEmpty()) return;
        Nodo origen = edges.get(0).origen;
        if (origen == null || origen.indice < 0 || origen.capacidad <= 0) return;
        long desde = aMinutoEpoch(batch.getTiempoListo());
        long firstDep = deps.get(0);
        if (firstDep <= desde) return;
        cargarAlmacenPierna(mapa, origen.indice, desde, firstDep, signo * batch.getCantidad());
    }

    private boolean cabeOrigen(LoteEnvio batch, List<Arista> edges, List<Long> deps,
                               Map<Long, Integer> blockAirport) {
        if (edges == null || edges.isEmpty() || deps == null || deps.isEmpty()) return true;
        Nodo origen = edges.get(0).origen;
        if (origen == null || origen.indice < 0 || origen.capacidad <= 0) return true;
        long desde = aMinutoEpoch(batch.getTiempoListo());
        long firstDep = deps.get(0);
        if (firstDep <= desde) return true;
        return cabeAlmacenPierna(origen, desde, firstDep, batch.getCantidad(), blockAirport);
    }

    // -----------------------------------------------------------------------
    // OcupaciÃ³n de origen por el backlog (envÃ­os sinRuta en espera)
    // -----------------------------------------------------------------------

    public LoteEnvio reconstruirEsperaOrigenBacklog(Collection<LoteEnvio> pendientes,
                                                       Collection<LoteEnvio> bloqueLote) {
        if (bloqueLote != null) {
            for (LoteEnvio b : bloqueLote) {
                if (b == null || b.getTiempoListo() == null) continue;
                relojUtcMin = Math.max(relojUtcMin, aMinutoEpoch(b.getTiempoListo()));
            }
        }
        ocupacionOrigenBacklog.clear();
        origenAdmitidos.clear();
        if (pendientes == null || relojUtcMin == Long.MIN_VALUE) return null;

        List<LoteEnvio> orden = new ArrayList<>();
        for (LoteEnvio b : pendientes) {
            if (b == null || b.getTiempoListo() == null) continue;
            if (b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty()) continue; // con ruta â‡’ ya contabilizado
            orden.add(b);
        }
        orden.sort(Comparator.comparingLong(b -> aMinutoEpoch(b.tiempoListoEfectivo())));
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
        if (batch.getRutaAsignada() != null && !batch.getRutaAsignada().isEmpty()) return;
        if (origenAdmitidos.remove(batch.getId())) {
            acumularEsperaOrigen(batch, -1);
        }
    }

    private boolean cabeEsperaOrigen(LoteEnvio batch) {
        Nodo origen = grafo.nodos.get(batch.origenEfectivo());
        if (origen == null || origen.indice < 0 || origen.capacidad <= 0) return true;
        long desde = aMinutoEpoch(batch.tiempoListoEfectivo());
        if (relojUtcMin <= desde) return true;
        return cabeAlmacenPierna(origen, desde, relojUtcMin, batch.getCantidad(), Map.of());
    }

    private void acumularEsperaOrigen(LoteEnvio batch, int signo) {
        if (batch == null || batch.tiempoListoEfectivo() == null || relojUtcMin == Long.MIN_VALUE) return;
        Nodo origen = grafo.nodos.get(batch.origenEfectivo());
        if (origen == null || origen.indice < 0 || origen.capacidad <= 0) return;
        long desde = aMinutoEpoch(batch.tiempoListoEfectivo());
        if (relojUtcMin <= desde) return;
        cargarAlmacenPierna(ocupacionOrigenBacklog, origen.indice, desde, relojUtcMin, signo * batch.getCantidad());
    }

    public void confirmarBloque(Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        blockFlight.forEach((key, qty) -> {
            if (qty != 0) ocupacionVuelo.merge(key, qty, Integer::sum);
        });
        blockAirport.forEach((key, qty) -> {
            if (qty != 0) ocupacionAeropuerto.merge(key, qty, Integer::sum);
        });
        // Redescubrir hubs desde la ocupaciÃ³n real cada N bloques (todos los escenarios).
        // Fuera del bucle caliente por-batch â†’ Ta-safe; el conjunto solo crece (sin flapping).
        if (++confirmacionesDesdeReclasificar >= HUB_RECLASIFICAR_CADA) {
            confirmacionesDesdeReclasificar = 0;
            reclasificarHubsPorUtilizacion(umbralHubPico);
        }
    }

    public void liberarDeGlobal(LoteEnvio batch) {
        List<Arista> route = batch.getRutaAsignada();
        List<Long> deps  = batch.getSalidasAsignadas();
        if (route == null || route.isEmpty() || deps == null || deps.isEmpty()) return;

        for (int i = 0; i < route.size(); i++) {
            Arista e      = route.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.duracionMinutos;

            ocupacionVuelo.merge(claveVuelo(e.indice, depMin), -batch.getCantidad(), Integer::sum);

            boolean esFinalLeg = (i == route.size() - 1);
            // Libera por slots de estadÃ­a (mismo intervalo que cargÃ³ aplicarABloque).
            if (!esFinalLeg && e.destino.indice >= 0) {
                cargarAlmacenPierna(ocupacionAeropuerto, e.destino.indice, arrMin, deps.get(i + 1),
                        -batch.getCantidad());
            } else if (esFinalLeg && e.destino.indice >= 0 && e.destino.capacidad > 0) {
                cargarAlmacenPierna(ocupacionAeropuerto, e.destino.indice, arrMin, arrMin + tiempoRecojoDestino,
                        -batch.getCantidad());
            }
        }
        // Libera la ocupaciÃ³n de origen en la ocupaciÃ³n global (espejo de aplicarABloque).
        cargarOrigen(ocupacionAeropuerto, batch, route, deps, -1);
    }

    public void liberarSufijoDeGlobal(LoteEnvio batch, int k) {
        List<Arista> route = batch.getRutaAsignada();
        List<Long> deps  = batch.getSalidasAsignadas();
        if (route == null || deps == null || deps.size() != route.size()) return;
        int n = route.size();
        if (k <= 0 || k >= n) return;

        // EstadÃ­a vieja del nodo de corte k-1: [arr_{k-1}, deps[k]).
        Arista corte = route.get(k - 1);
        if (corte.destino != null && corte.destino.indice >= 0) {
            long arrCorte = deps.get(k - 1) + corte.duracionMinutos;
            cargarAlmacenPierna(ocupacionAeropuerto, corte.destino.indice, arrCorte, deps.get(k),
                    -batch.getCantidad());
        }
        // Sufijo: vuelos y estadÃ­as de destino, desde k.
        for (int i = k; i < n; i++) {
            Arista e      = route.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.duracionMinutos;
            ocupacionVuelo.merge(claveVuelo(e.indice, depMin), -batch.getCantidad(), Integer::sum);
            boolean esFinalLeg = (i == n - 1);
            if (!esFinalLeg && e.destino.indice >= 0) {
                cargarAlmacenPierna(ocupacionAeropuerto, e.destino.indice, arrMin, deps.get(i + 1),
                        -batch.getCantidad());
            } else if (esFinalLeg && e.destino.indice >= 0 && e.destino.capacidad > 0) {
                cargarAlmacenPierna(ocupacionAeropuerto, e.destino.indice, arrMin, arrMin + tiempoRecojoDestino,
                        -batch.getCantidad());
            }
        }
    }

    public boolean cumpleSlaDesdeOrigen(RutaCandidata sufijo, LoteEnvio original) {
        if (sufijo == null || original == null) return false;
        long deadlineMin = aMinutoEpoch(original.getTiempoListo()) + (long) original.getHorasLimiteSla() * 60L;
        return (sufijo.getLlegadaMin() + tiempoRecojoDestino) <= deadlineMin;
    }

    // -----------------------------------------------------------------------
    // Dijkstra earliest-arrival con capacidad global + bloque
    // -----------------------------------------------------------------------

    private ResultadoRuta buscarRutaMasCorta(LoteEnvio batch,
                                          Map<Long, Integer> blockFlight,
                                          Map<Long, Integer> blockAirport) {
        return buscarRutaMasCorta(batch, blockFlight, blockAirport, false);
    }

    public boolean sinRutaPorAlmacenLleno(LoteEnvio batch) {
        if (batch == null) return false;
        ResultadoRuta con = buscarRutaMasCorta(batch, Map.of(), Map.of(), false);
        if (con.cumpleSLA && !con.aristas.isEmpty()) return false;  // sÃ­ habÃ­a ruta on-time â†’ no fue almacÃ©n
        ResultadoRuta sin = buscarRutaMasCorta(batch, Map.of(), Map.of(), true);
        return sin.cumpleSLA && !sin.aristas.isEmpty();
    }

    private ResultadoRuta buscarRutaMasCorta(LoteEnvio batch,
                                          Map<Long, Integer> blockFlight,
                                          Map<Long, Integer> blockAirport,
                                          boolean ignorarAlmacen) {
        Nodo startNodeObj  = grafo.nodos.get(batch.getCodigoOrigen());
        Nodo targetNodeObj = grafo.nodos.get(batch.getCodigoDestino());
        if (startNodeObj == null || targetNodeObj == null) return ResultadoRuta.EMPTY;

        int startIdx      = startNodeObj.indice;
        int targetNodeIdx = targetNodeObj.indice;
        if (startIdx < 0 || targetNodeIdx < 0) return ResultadoRuta.EMPTY;

        long readyMin      = aMinutoEpoch(batch.getTiempoListo());
        long readyDay      = readyMin / MIN_DIA;
        long slaMaxMinutes = (long) batch.getHorasLimiteSla() * 60;

        long[] bestTimes = new long[conteoNodos * SLOTS_DIA];
        Arrays.fill(bestTimes, Long.MAX_VALUE);

        PriorityQueue<EstadoRuta> pq = new PriorityQueue<>(Comparator.comparingLong(s -> s.arrivalMin));

        long horizonDays = HORIZONTE_MAX_MIN / MIN_DIA;
        for (long d = 0; d <= horizonDays; d++) {
            long startMin = readyMin + d * MIN_DIA;
            bestTimes[startIdx * SLOTS_DIA + (int)d] = startMin;
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
                        && !cabeEstadiasRuta(edges, deps, batch.getCantidad(), blockAirport)) {
                    continue;
                }
                long transitMinutes = (current.arrivalMin + tiempoRecojoDestino) - readyMin;
                return new ResultadoRuta(edges, deps, transitMinutes <= slaMaxMinutes);
            }

            for (Arista flight : adyacenciaPorIndice[current.nodeIdx]) {
                long minWait  = (current.edge == null) ? 0L : conexionMin;
                long earliest = current.arrivalMin + minWait;

                long actualDep = proximaSalidaMin(flight.minutoDelDiaSalida, earliest);
                long actualArr = actualDep + flight.duracionMinutos;

                long dayOffset = actualArr / MIN_DIA - readyDay;
                if (dayOffset < 0 || dayOffset >= SLOTS_DIA) continue;
                if (actualArr - readyMin > HORIZONTE_MAX_MIN) continue;

                if (!ignorarAlmacen && current.edge == null
                        && !cabeAlmacenPierna(startNodeObj, readyMin, actualDep,
                                batch.getCantidad(), blockAirport)) continue;

                if (vueloRestante(flight, actualDep, blockFlight) < batch.getCantidad()) continue;

                int nextIdx = flight.destino.indice;
                if (nextIdx < 0) continue;
                if (!ignorarAlmacen && flight.destino.capacidad > 0) {
                    int qty = batch.getCantidad();
                    long ak = claveAeropuerto(nextIdx, actualArr);
                    if (ocupacionAeropuerto.getOrDefault(ak, 0) + blockAirport.getOrDefault(ak, 0)
                            + ocupacionOrigenBacklog.getOrDefault(ak, 0) + qty
                            > flight.destino.capacidad)
                        continue;
                }

                int cell = nextIdx * SLOTS_DIA + (int)dayOffset;
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

        Nodo startNodeObj = grafo.nodos.get(batch.getCodigoOrigen());
        Nodo targetNodeObj = grafo.nodos.get(batch.getCodigoDestino());
        if (startNodeObj == null || targetNodeObj == null) return Collections.emptyList();

        int startIdx = startNodeObj.indice;
        int targetIdx = targetNodeObj.indice;
        if (startIdx < 0 || targetIdx < 0) return Collections.emptyList();

        long readyMin = aMinutoEpoch(batch.getTiempoListo());
        long readyDay = readyMin / MIN_DIA;
        long slaMaxMinutes = (long) batch.getHorasLimiteSla() * 60L;

        long skKey = skeletonKey(startIdx, targetIdx, readyMin, batch.getHorasLimiteSla());
        List<int[]> cachedSk = rutaCacheEsqueleto.get(skKey);
        if (cachedSk != null && !cachedSk.isEmpty()) {
            List<RutaCandidata> reuso = new ArrayList<>(cachedSk.size());
            Set<String> firmasReuso = new HashSet<>();
            for (int[] sk : cachedSk) {
                RutaCandidata c = materializarEsqueleto(batch, sk, blockFlight, blockAirport);
                if (c == null) continue;
                if (firmasReuso.add(c.signature())) reuso.add(c);
            }
            if (reuso.size() >= maxCandidatos) {
                reuso.sort(OperadorReparacionVoraz::compararCandidatosRuta);
                if (reuso.get(0).cumpleSLA) {
                    return reuso.size() <= maxCandidatos ? reuso
                            : new ArrayList<>(reuso.subList(0, maxCandidatos));
                }
            }
        }

        int labelsPorCelda = Math.max(1, Math.min(4, maxCandidatos + 1));
        @SuppressWarnings("unchecked")
        List<EtiquetaRuta>[] labels = new List[conteoNodos * SLOTS_DIA];

        PriorityQueue<EstadoRuta> pq = new PriorityQueue<>(
                Comparator.comparingLong((EstadoRuta s) -> s.arrivalMin)
                        .thenComparingInt(s -> s.legs));

        long horizonDays = HORIZONTE_MAX_MIN / MIN_DIA;
        for (long d = 0; d <= horizonDays; d++) {
            long startMin = readyMin + d * MIN_DIA;
            int cell = startIdx * SLOTS_DIA + (int) d;
            EtiquetaRuta label = new EtiquetaRuta(startMin, 0, 0.0);
            addLabel(labels, cell, label, labelsPorCelda);
            pq.add(new EstadoRuta(startIdx, startMin, -1L, null, null, 0, 0.0));
        }

        int limiteObjetivo = maxCandidatos <= 1 ? 1 : Math.max(maxCandidatos, maxCandidatos * 2);
        int maxExpansiones = Math.max(256, maxCandidatos * Math.max(1, conteoNodos) * SLOTS_DIA * 8);
        int expansiones = 0;
        int candidatosOnTime = 0;
        List<RutaCandidata> candidatos = new ArrayList<>(limiteObjetivo);
        Set<String> firmas = new HashSet<>();

        while (!pq.isEmpty() && candidatos.size() < limiteObjetivo && expansiones++ < maxExpansiones) {
            EstadoRuta current = pq.poll();

            if (current.nodeIdx == targetIdx && current.edge != null) {
                RutaCandidata candidate = aCandidatoRuta(current, batch, readyMin, slaMaxMinutes,
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

            if (current.legs >= MAX_TRAMOS_CANDIDATO) continue;

            for (Arista flight : adyacenciaPorIndice[current.nodeIdx]) {
                if (flight.destino == null || flight.destino.indice < 0) continue;
                int nextIdx = flight.destino.indice;
                if (contieneNodo(current, nextIdx)) continue;

                long minWait = (current.edge == null) ? 0L : conexionMin;
                long earliest = current.arrivalMin + minWait;
                long actualDep = proximaSalidaMin(flight.minutoDelDiaSalida, earliest);
                long actualArr = actualDep + flight.duracionMinutos;

                long dayOffset = actualArr / MIN_DIA - readyDay;
                if (dayOffset < 0 || dayOffset >= SLOTS_DIA) continue;
                if (actualArr - readyMin > HORIZONTE_MAX_MIN) continue;
                if (candidatosOnTime >= maxCandidatos
                        && (actualArr + tiempoRecojoDestino) - readyMin > slaMaxMinutes) {
                    continue;
                }
                // Primer vuelo viable solo si la espera en almacÃ©n de origen cabe.
                if (current.edge == null
                        && !cabeAlmacenPierna(startNodeObj, readyMin, actualDep,
                                batch.getCantidad(), blockAirport)) continue;
                if (vueloRestante(flight, actualDep, blockFlight) < batch.getCantidad()) continue;
                if (!tieneCapacidadAeropuerto(flight.destino, nextIdx == targetIdx, actualArr, batch.getCantidad(), blockAirport)) {
                    continue;
                }

                int cell = nextIdx * SLOTS_DIA + (int) dayOffset;
                double pressure = Math.max(current.pressure, presionPasoProyectada(
                        flight, actualDep, nextIdx == targetIdx, batch.getCantidad(), blockFlight, blockAirport));
                EtiquetaRuta label = new EtiquetaRuta(actualArr, current.legs + 1, pressure);
                if (isDominated(labels[cell], label)) continue;
                addLabel(labels, cell, label, labelsPorCelda);
                pq.add(new EstadoRuta(nextIdx, actualArr, actualDep, flight, current,
                        current.legs + 1, pressure));
            }
        }

        candidatos.sort(OperadorReparacionVoraz::compararCandidatosRuta);

        // Guardar los esqueletos hallados para reusarlos en bloques futuros.
        if (!candidatos.isEmpty()) {
            List<int[]> sks = new ArrayList<>(Math.min(candidatos.size(), MAX_ESQUELETOS_POR_CLAVE));
            for (RutaCandidata c : candidatos) {
                if (sks.size() >= MAX_ESQUELETOS_POR_CLAVE) break;
                List<Arista> es = c.getAristas();
                int[] arr = new int[es.size()];
                for (int i = 0; i < es.size(); i++) arr[i] = es.get(i).indice;
                sks.add(arr);
            }
            rutaCacheEsqueleto.put(skKey, sks);
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
            if (b == null || b.getTiempoListo() == null) continue;
            Nodo o = grafo.nodos.get(b.getCodigoOrigen());
            Nodo d = grafo.nodos.get(b.getCodigoDestino());
            if (o == null || d == null || o.indice < 0 || d.indice < 0) continue;
            long key = skeletonKey(o.indice, d.indice, aMinutoEpoch(b.getTiempoListo()), b.getHorasLimiteSla());
            if (!vistas.add(key)) continue;                  // un intento por clave Ãºnica
            generarCandidatosRuta(b, bf, ba, maxCandidatos); // popula rutaCacheEsqueleto en el miss
            calentadas++;
        }
        return calentadas;
    }

    static long skeletonKey(int startIdx, int targetIdx, long readyMin, int slaHours) {   // package-private para tests
        long hourBucket = (readyMin % MIN_DIA) / BUCKET_ESQUELETO_MIN;   // 0..23
        return ((long) startIdx << 40)
                | ((long) targetIdx << 24)
                | (hourBucket << 8)
                | (slaHours & 0xFFL);
    }

    private RutaCandidata materializarEsqueleto(LoteEnvio batch,
                                                int[] edgeIdxs,
                                                Map<Long, Integer> blockFlight,
                                                Map<Long, Integer> blockAirport) {
        if (edgeIdxs == null || edgeIdxs.length == 0) return null;
        List<Arista> ruta = new ArrayList<>(edgeIdxs.length);
        for (int idx : edgeIdxs) {
            if (idx < 0 || idx >= aristaPorIndice.length) return null;
            Arista e = aristaPorIndice[idx];
            if (e == null) return null;
            ruta.add(e);
        }
        return materializarRutaCandidata(batch, ruta, blockFlight, blockAirport);
    }

    // -----------------------------------------------------------------------
    // Helpers privados
    // -----------------------------------------------------------------------

    private void aplicarABloque(LoteEnvio batch, ResultadoRuta result,
                               Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport) {
        for (int i = 0; i < result.aristas.size(); i++) {
            Arista e      = result.aristas.get(i);
            long depMin = result.salidasReales.get(i);
            long arrMin = depMin + e.duracionMinutos;

            blockFlight.merge(claveVuelo(e.indice, depMin), batch.getCantidad(), Integer::sum);

            boolean esFinalLeg = (i == result.aristas.size() - 1);
            // Carga por OCUPACIÃ“N concurrente: cada slot de la estadÃ­a real de la pierna.
            if (!esFinalLeg && e.destino.indice >= 0) {
                long salida = result.salidasReales.get(i + 1);   // hasta que sale el siguiente vuelo
                cargarAlmacenPierna(blockAirport, e.destino.indice, arrMin, salida, batch.getCantidad());
            } else if (esFinalLeg && e.destino.indice >= 0 && e.destino.capacidad > 0) {
                // Destino final: la maleta se retira ~tiempoRecojoDestino tras aterrizar (1 slot).
                cargarAlmacenPierna(blockAirport, e.destino.indice, arrMin, arrMin + tiempoRecojoDestino,
                        batch.getCantidad());
            }
        }
        cargarOrigen(blockAirport, batch, result.aristas, result.salidasReales, +1);
    }

    private long proximaSalidaMin(int depMinuteOfDay, long earliest) {
        long dayStart  = (earliest / MIN_DIA) * MIN_DIA;
        long candidate = dayStart + depMinuteOfDay;
        return candidate < earliest ? candidate + MIN_DIA : candidate;
    }

    private int vueloRestante(Arista flight, long depMin, Map<Long, Integer> blockFlight) {
        long key = claveVuelo(flight.indice, depMin);
        if (vueloDiasCancelados.contains(key)) return 0;  // vuelo cancelado ese dÃ­a
        return flight.capacidad
             - ocupacionVuelo.getOrDefault(key, 0)
             - blockFlight.getOrDefault(key, 0);
    }

    public long calcularProximaSalida(int depMinuteOfDay, long earliest) {
        return proximaSalidaMin(depMinuteOfDay, earliest);
    }

    public int capacidadRestante(Arista flight, long depMin, Map<Long, Integer> blockFlight) {
        return vueloRestante(flight, depMin, blockFlight);
    }

    public int capacidadAlmacen(Nodo node, long arrMin, Map<Long, Integer> blockAirport) {
        if (node == null || node.indice < 0 || node.capacidad <= 0) return Integer.MAX_VALUE;
        long key = claveAeropuerto(node.indice, arrMin);
        return node.capacidad
             - ocupacionAeropuerto.getOrDefault(key, 0)
             - blockAirport.getOrDefault(key, 0)
             - ocupacionOrigenBacklog.getOrDefault(key, 0);
    }

    public int ocupacionGlobalVuelo(long claveVuelo) {
        return ocupacionVuelo.getOrDefault(claveVuelo, 0);
    }

    public int ocupacionGlobalAlmacen(long claveSlot) {
        return ocupacionAeropuerto.getOrDefault(claveSlot, 0)
             + ocupacionOrigenBacklog.getOrDefault(claveSlot, 0);
    }

    public void aplicarAsignacionBloque(LoteEnvio batch,
                                         Map<Long, Integer> blockFlight,
                                         Map<Long, Integer> blockAirport) {
        List<Arista> route = batch.getRutaAsignada();
        List<Long> deps  = batch.getSalidasAsignadas();
        if (route == null || route.isEmpty() || deps == null || deps.size() != route.size()) return;
        ResultadoRuta fake = new ResultadoRuta(route, deps, batch.isCumpleSLA());
        aplicarABloque(batch, fake, blockFlight, blockAirport);
    }

    public static long aMinutoEpochPublico(LocalDateTime dt) {
        return aMinutoEpoch(dt);
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

        long readyMin = aMinutoEpoch(batch.getTiempoListo());
        long slaMaxMinutes = (long) batch.getHorasLimiteSla() * 60L;
        long earliest = readyMin;
        String expectedFrom = batch.getCodigoOrigen();
        List<Arista> edges = new ArrayList<>(ruta.size());
        List<Long> deps = new ArrayList<>(ruta.size());

        for (int i = 0; i < ruta.size(); i++) {
            Arista edge = ruta.get(i);
            if (edge == null || edge.origen == null || edge.destino == null) return null;
            if (!Objects.equals(expectedFrom, edge.origen.codigo)) return null;

            boolean finalLeg = i == ruta.size() - 1;
            if (finalLeg && !Objects.equals(batch.getCodigoDestino(), edge.destino.codigo)) return null;

            long minDeparture = (i == 0) ? earliest : earliest + conexionMin;
            long depMin = proximaSalidaMin(edge.minutoDelDiaSalida, minDeparture);
            long arrMin = depMin + edge.duracionMinutos;
            boolean found = false;
            while (arrMin - readyMin <= HORIZONTE_MAX_MIN) {
                boolean origenOk = i != 0
                        || cabeAlmacenPierna(edge.origen, readyMin, depMin,
                                batch.getCantidad(), blockAirport);
                if (origenOk
                        && vueloRestante(edge, depMin, blockFlight) >= batch.getCantidad()
                        && tieneCapacidadAeropuerto(edge.destino, finalLeg, arrMin, batch.getCantidad(), blockAirport)) {
                    found = true;
                    break;
                }
                depMin += MIN_DIA;
                arrMin = depMin + edge.duracionMinutos;
            }
            if (!found) return null;

            edges.add(edge);
            deps.add(depMin);
            earliest = arrMin;
            expectedFrom = edge.destino.codigo;
        }

        return aCandidatoRuta(edges, deps, batch, readyMin, slaMaxMinutes, blockFlight, blockAirport);
    }

    public void aplicarCandidatoRuta(LoteEnvio batch, RutaCandidata candidate) {
        if (batch == null || candidate == null) return;
        batch.setRutaAsignada(new ArrayList<>(candidate.aristas));
        batch.setSalidasAsignadas(new ArrayList<>(candidate.salidasReales));
        batch.setCumpleSLA(candidate.cumpleSLA);
    }

    public void aplicarCandidatoBloque(LoteEnvio batch,
                                       RutaCandidata candidate,
                                       Map<Long, Integer> blockFlight,
                                       Map<Long, Integer> blockAirport) {
        if (batch == null || candidate == null) return;
        ResultadoRuta fake = new ResultadoRuta(candidate.aristas, candidate.salidasReales, candidate.cumpleSLA);
        aplicarABloque(batch, fake, blockFlight, blockAirport);
    }

    public boolean rutaSirveParaLote(RutaCandidata candidate,
                                      LoteEnvio batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport) {
        return rutaSirveParaLote(candidate, batch, blockFlight, blockAirport, 0.0, 0.0);
    }

    public boolean rutaSirveParaLote(RutaCandidata candidate,
                                      LoteEnvio batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport,
                                      double reservaBase) {
        return rutaSirveParaLote(candidate, batch, blockFlight, blockAirport, reservaBase, 0.0);
    }

    public boolean rutaSirveParaLote(RutaCandidata candidate,
                                      LoteEnvio batch,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport,
                                      double reservaBase,
                                      double reservaAlmacenBase) {
        if (candidate == null || batch == null) return false;
        List<Arista> edges = candidate.getAristas();
        List<Long> deps = candidate.getSalidasReales();
        if (edges.isEmpty() || deps.size() != edges.size()) return false;

        long readyMin = aMinutoEpoch(batch.getTiempoListo());
        if (deps.get(0) < readyMin) return false;   // el envÃ­o aÃºn no estaba listo

        long arrMin = deps.get(deps.size() - 1) + edges.get(edges.size() - 1).duracionMinutos;
        long transitMin = (arrMin + tiempoRecojoDestino) - readyMin;
        if (transitMin > (long) batch.getHorasLimiteSla() * 60L) return false;   // tardarÃ­a

        // ColchÃ³n de reserva proporcional a la holgura (urgente â‡’ 0 â‡’ sin reserva).
        double slackRatio = 0.0;
        if (reservaBase > 0.0 || reservaAlmacenBase > 0.0) {
            double slaMin = Math.max(1.0, batch.getHorasLimiteSla() * 60.0);
            slackRatio = Math.max(0.0, Math.min(1.0, candidate.getHolguraMin() / slaMin));
        }
        double reservaVuelo = reservaBase * slackRatio;
        double reservaAlmacen = reservaAlmacenBase * slackRatio;

        int qty = batch.getCantidad();
        for (int i = 0; i < edges.size(); i++) {
            Arista e = edges.get(i);
            long depMin = deps.get(i);
            int colchonVuelo = reservaVuelo > 0.0 && e.capacidad > 0
                    ? (int) Math.ceil(reservaVuelo * e.capacidad) : 0;
            if (vueloRestante(e, depMin, blockFlight) < qty + colchonVuelo) return false;
            boolean finalLeg = (i == edges.size() - 1);
            long llegada = depMin + e.duracionMinutos;
            // Capacidad CONCURRENTE: la estadÃ­a real ocupa [llegada, salida); cada slot
            // del intervalo debe caber. Destino final â‰ˆ tiempoRecojoDestino; escala = hasta salir.
            long salida = finalLeg ? llegada + tiempoRecojoDestino : deps.get(i + 1);
            if (!cabeAlmacenPierna(e.destino, llegada, salida, qty, blockAirport)) {
                return false;
            }
            // ColchÃ³n en almacÃ©n de HUB para la estadÃ­a de una ESCALA (no destino final).
            // Protege el storage concurrente de hub para los envÃ­os urgentes/24h.
            if (!finalLeg && reservaAlmacen > 0.0 && e.destino != null && e.destino.capacidad > 0
                    && esHub(e.destino.indice)) {
                int colchonAlm = (int) Math.ceil(reservaAlmacen * e.destino.capacidad);
                if (!cabeAlmacenPierna(e.destino, llegada, salida, qty + colchonAlm, blockAirport)) {
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
        List<Arista> edges = candidate.getAristas();
        List<Long> deps = candidate.getSalidasReales();
        if (edges.isEmpty() || deps.size() != edges.size()) return Collections.emptySet();

        Set<Long> keys = new HashSet<>(edges.size() * 3);
        // Incluye los slots de espera en el almacÃ©n de origen.
        if (batch != null) {
            Nodo origen = edges.get(0).origen;
            if (origen != null && origen.indice >= 0 && origen.capacidad > 0) {
                long desde = aMinutoEpoch(batch.getTiempoListo());
                long firstDep = deps.get(0);
                if (firstDep > desde) agregarSlotsEstadia(keys, origen.indice, desde, firstDep);
            }
        }
        for (int i = 0; i < edges.size(); i++) {
            Arista e = edges.get(i);
            long depMin = deps.get(i);
            long arrMin = depMin + e.duracionMinutos;

            keys.add(claveVuelo(e.indice, depMin));

            boolean esFinalLeg = (i == edges.size() - 1);
            // Claves por SLOT de la estadÃ­a concurrente (mismo intervalo que aplicarABloque).
            if (!esFinalLeg && e.destino.indice >= 0) {
                agregarSlotsEstadia(keys, e.destino.indice, arrMin, deps.get(i + 1));
            } else if (esFinalLeg && e.destino.indice >= 0 && e.destino.capacidad > 0) {
                agregarSlotsEstadia(keys, e.destino.indice, arrMin, arrMin + tiempoRecojoDestino);
            }
        }
        return keys;
    }

    private static long claveVuelo(int edgeIdx, long epochMin) {
        return CodificadorClaveVuelo.claveVuelo(edgeIdx, epochMin);
    }

    private static long claveAeropuerto(int nodeIdx, long epochMin) {
        return claveSlot(nodeIdx, epochMin / SLOT_ALMACEN_MIN);
    }

    private static long claveSlot(int nodeIdx, long slot) {
        return (((long) nodeIdx) << BITS_DIA) | (slot & CodificadorClaveVuelo.MASCARA_DIA);
    }

    public static long claveAlmacenDeSlot(int nodeIdx, long epochMin) {
        return claveAeropuerto(nodeIdx, epochMin);
    }

    private static long ultimoSlot(long llegada, long salida) {
        return (Math.max(llegada + 1, salida) - 1) / SLOT_ALMACEN_MIN;
    }

    private void agregarSlotsEstadia(Set<Long> keys, int nodeIdx, long llegada, long salida) {
        if (nodeIdx < 0) return;
        long s1 = ultimoSlot(llegada, salida);
        for (long s = llegada / SLOT_ALMACEN_MIN; s <= s1; s++) {
            keys.add(claveSlot(nodeIdx, s));
        }
    }

    private void cargarAlmacenPierna(Map<Long, Integer> mapa, int nodeIdx,
                                     long llegada, long salida, int delta) {
        if (nodeIdx < 0) return;
        long s1 = ultimoSlot(llegada, salida);
        for (long s = llegada / SLOT_ALMACEN_MIN; s <= s1; s++) {
            mapa.merge(claveSlot(nodeIdx, s), delta, Integer::sum);
        }
    }

    private boolean cabeEstadiasRuta(List<Arista> edges, List<Long> deps, int qty,
                                     Map<Long, Integer> blockAirport) {
        for (int i = 0; i < edges.size(); i++) {
            Arista e = edges.get(i);
            long llegada = deps.get(i) + e.duracionMinutos;
            long salida = (i < edges.size() - 1) ? deps.get(i + 1) : llegada + tiempoRecojoDestino;
            if (!cabeAlmacenPierna(e.destino, llegada, salida, qty, blockAirport)) return false;
        }
        return true;
    }

    private boolean cabeAlmacenPierna(Nodo node, long llegada, long salida, int qty,
                                      Map<Long, Integer> blockAirport) {
        if (node == null || node.indice < 0 || node.capacidad <= 0) return true;
        long s1 = ultimoSlot(llegada, salida);
        for (long s = llegada / SLOT_ALMACEN_MIN; s <= s1; s++) {
            long k = claveSlot(node.indice, s);
            if (ocupacionAeropuerto.getOrDefault(k, 0) + blockAirport.getOrDefault(k, 0)
                    + ocupacionOrigenBacklog.getOrDefault(k, 0) + qty > node.capacidad) {
                return false;
            }
        }
        return true;
    }

    private static long aMinutoEpoch(LocalDateTime dt) {
        return dt.toLocalDate().toEpochDay() * MIN_DIA + dt.getHour() * 60L + dt.getMinute();
    }

    private boolean tieneCapacidadAeropuerto(Nodo node,
                                       boolean destinoFinal,
                                       long arrMin,
                                       int qty,
                                       Map<Long, Integer> blockAirport) {
        if (node == null || node.indice < 0 || node.capacidad <= 0) return true;
        long ak = claveAeropuerto(node.indice, arrMin);
        return ocupacionAeropuerto.getOrDefault(ak, 0) + blockAirport.getOrDefault(ak, 0)
                + ocupacionOrigenBacklog.getOrDefault(ak, 0) + qty <= node.capacidad;
    }

    private RutaCandidata aCandidatoRuta(EstadoRuta state,
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
        return aCandidatoRuta(edges, deps, batch, readyMin, slaMaxMinutes, blockFlight, blockAirport);
    }

    private RutaCandidata aCandidatoRuta(List<Arista> edges,
                                            List<Long> deps,
                                            LoteEnvio batch,
                                            long readyMin,
                                            long slaMaxMinutes,
                                            Map<Long, Integer> blockFlight,
                                            Map<Long, Integer> blockAirport) {
        if (edges.isEmpty() || deps.size() != edges.size()) return null;

        if (!cabeEstadiasRuta(edges, deps, batch.getCantidad(), blockAirport)) return null;

        long arrivalMin = deps.get(deps.size() - 1) + edges.get(edges.size() - 1).duracionMinutos;
        long transitMin = (arrivalMin + tiempoRecojoDestino) - readyMin;
        long slackMin = slaMaxMinutes - transitMin;
        double pressure = presionProyectada(edges, deps, batch, blockFlight, blockAirport);
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
            if (e.capacidad > 0) {
                int remaining = vueloRestante(e, depMin, blockFlight);
                sum += precioCongestion(e.capacidad - remaining, e.capacidad);
            }
            if (e.destino != null && e.destino.indice >= 0 && e.destino.capacidad > 0) {
                long arrMin = depMin + e.duracionMinutos;
                boolean transito = i < edges.size() - 1;   // escala (no destino final)
                // L1: en una escala de HUB el almacÃ©n es el cuello â†’ curva que muerde antes.
                // El destino final (delivery) y las escalas no-hub conservan la curva base.
                boolean hubTransito = transito && esHub(e.destino.indice);
                int remaining = capacidadAlmacen(e.destino, arrMin, blockAirport);
                int usadoArr = e.destino.capacidad - remaining;
                sum += hubTransito
                        ? precioCongestionAlmacenHub(usadoArr, e.destino.capacidad)
                        : precioCongestion(usadoArr, e.destino.capacidad);
                if (transito) {
                    // Fase R â€” muestrea el slot de fin de estadÃ­a (salida del siguiente vuelo).
                    int remNext = capacidadAlmacen(e.destino, deps.get(i + 1), blockAirport);
                    int usadoNext = e.destino.capacidad - remNext;
                    sum += hubTransito
                            ? precioCongestionAlmacenHub(usadoNext, e.destino.capacidad)
                            : precioCongestion(usadoNext, e.destino.capacidad);
                }
            }
        }
        return sum;
    }

    private double presionPasoProyectada(Arista edge,
                                         long depMin,
                                         boolean destinoFinal,
                                         int qty,
                                         Map<Long, Integer> blockFlight,
                                         Map<Long, Integer> blockAirport) {
        double max = 0.0;
        if (edge.capacidad > 0) {
            int remaining = vueloRestante(edge, depMin, blockFlight);
            max = Math.max(max, (double) (edge.capacidad - remaining + qty) / edge.capacidad);
        }
        if (edge.destino != null && edge.destino.indice >= 0 && edge.destino.capacidad > 0) {
            long arrMin = depMin + edge.duracionMinutos;
            int remaining = capacidadAlmacen(edge.destino, arrMin, blockAirport);
            max = Math.max(max, (double) (edge.destino.capacidad - remaining + qty) / edge.destino.capacidad);
            if (!destinoFinal) {
                // Fase R â€” proxy de presencia continua: muestrea el slot siguiente al de llegada.
                int remainingNextDay = capacidadAlmacen(edge.destino, arrMin + SLOT_ALMACEN_MIN, blockAirport);
                max = Math.max(max, (double) (edge.destino.capacidad - remainingNextDay + qty) / edge.destino.capacidad);
            }
        }
        return max;
    }

    private double presionProyectada(List<Arista> edges,
                                     List<Long> deps,
                                     LoteEnvio batch,
                                     Map<Long, Integer> blockFlight,
                                     Map<Long, Integer> blockAirport) {
        double max = 0.0;
        int qty = batch.getCantidad();
        for (int i = 0; i < edges.size(); i++) {
            Arista e = edges.get(i);
            long depMin = deps.get(i);
            if (e.capacidad > 0) {
                int remaining = vueloRestante(e, depMin, blockFlight);
                max = Math.max(max, (double) (e.capacidad - remaining + qty) / e.capacidad);
            }

            if (e.destino != null && e.destino.indice >= 0 && e.destino.capacidad > 0) {
                long arrMin = depMin + e.duracionMinutos;
                int remaining = capacidadAlmacen(e.destino, arrMin, blockAirport);
                max = Math.max(max, (double) (e.destino.capacidad - remaining + qty) / e.destino.capacidad);
                if (i < edges.size() - 1) {
                    // Fase R â€” muestrea el slot de fin de estadÃ­a (salida del siguiente vuelo).
                    int remainingNextDay = capacidadAlmacen(e.destino, deps.get(i + 1), blockAirport);
                    max = Math.max(max, (double) (e.destino.capacidad - remainingNextDay + qty) / e.destino.capacidad);
                }
            }
        }
        return max;
    }

    private boolean contieneNodo(EstadoRuta state, int nodeIdx) {
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

    private static int compararCandidatosRuta(RutaCandidata a, RutaCandidata b) {
        int c = Boolean.compare(b.cumpleSLA, a.cumpleSLA);
        if (c != 0) return c;
        c = Long.compare(Math.max(0L, -a.slackMin), Math.max(0L, -b.slackMin));
        if (c != 0) return c;
        c = Long.compare(a.transitMin, b.transitMin);
        if (c != 0) return c;
        c = Double.compare(a.pressure, b.pressure);
        if (c != 0) return c;
        c = Integer.compare(a.aristas.size(), b.aristas.size());
        if (c != 0) return c;
        return Long.compare(b.slackMin, a.slackMin);
    }

    // -----------------------------------------------------------------------
    // DiagnÃ³stico
    // -----------------------------------------------------------------------

    public void logEstadisticasCapacidad() {
        log.info("--- Capacidad de vuelos ---");
        long flightDaysUsados = ocupacionVuelo.size();
        long flightDaysLlenos = 0, flightDaysSobre = 0, totalAsignado = 0, totalCapacidad = 0;
        List<String> sobre = new ArrayList<>();

        for (Map.Entry<Long, Integer> entry : ocupacionVuelo.entrySet()) {
            int edgeIdx  = (int)(entry.getKey() >> BITS_DIA);
            int asignado = entry.getValue();
            totalAsignado += asignado;
            if (edgeIdx < aristaPorIndice.length && aristaPorIndice[edgeIdx] != null) {
                int cap = aristaPorIndice[edgeIdx].capacidad;
                totalCapacidad += cap;
                if (asignado >= cap) flightDaysLlenos++;
                if (asignado > cap) {
                    flightDaysSobre++;
                    sobre.add(aristaPorIndice[edgeIdx].id + "=" + asignado + "/" + cap);
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
        long airportDaysUsados = ocupacionAeropuerto.size();
        long airportDaysLlenos = 0, airportDaysSobre = 0, totalAirportAsig = 0, totalAirportCap = 0;
        Map<String, long[]> porAero = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : ocupacionAeropuerto.entrySet()) {
            int    nodeIdx  = (int)(entry.getKey() >> BITS_DIA);
            int    asignado = entry.getValue();
            totalAirportAsig += asignado;
            String code = (nodeIdx < nodoPorIndice.length) ? nodoPorIndice[nodeIdx] : "?";
            Nodo nodo   = grafo.nodos.get(code);
            int cap = (nodo != null && nodo.capacidad > 0) ? nodo.capacidad : -1;
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
        private final List<Arista> aristas;
        private final List<Long> salidasReales;
        private final boolean cumpleSLA;
        private final long arrivalMin;
        private final long transitMin;
        private final long slackMin;
        private final double pressure;
        private final double costoEscasez;
        private String cacheFirma;

        private RutaCandidata(List<Arista> edges,
                               List<Long> salidasReales,
                               boolean cumpleSLA,
                               long arrivalMin,
                               long transitMin,
                               long slackMin,
                               double pressure,
                               double costoEscasez) {
            this.aristas = List.copyOf(edges);
            this.salidasReales = List.copyOf(salidasReales);
            this.cumpleSLA = cumpleSLA;
            this.arrivalMin = arrivalMin;
            this.transitMin = transitMin;
            this.slackMin = slackMin;
            this.pressure = pressure;
            this.costoEscasez = costoEscasez;
        }

        public List<Arista> getAristas() { return aristas; }
        public List<Long> getSalidasReales() { return salidasReales; }
        public boolean isCumpleSLA() { return cumpleSLA; }
        public long getLlegadaMin() { return arrivalMin; }
        public long getTransitoMin() { return transitMin; }
        public long getHolguraMin() { return slackMin; }
        public double getPresion() { return pressure; }
        public double getCostoEscasez() { return costoEscasez; }
        public int getTramos() { return aristas.size(); }

        public String signature() {
            String cached = cacheFirma;
            if (cached != null) return cached;
            StringBuilder sb = new StringBuilder(aristas.size() * 12);
            for (int i = 0; i < aristas.size(); i++) {
                sb.append(aristas.get(i).indice).append('@').append(salidasReales.get(i)).append(';');
            }
            cached = sb.toString();
            cacheFirma = cached;
            return cached;
        }
    }

    private static class ResultadoRuta {
        final List<Arista> aristas;
        final List<Long> salidasReales;
        final boolean    cumpleSLA;

        static final ResultadoRuta EMPTY =
                new ResultadoRuta(Collections.emptyList(), Collections.emptyList(), false);

        ResultadoRuta(List<Arista> edges, List<Long> salidasReales, boolean cumpleSLA) {
            this.aristas            = edges;
            this.salidasReales = salidasReales;
            this.cumpleSLA        = cumpleSLA;
        }
    }
}
