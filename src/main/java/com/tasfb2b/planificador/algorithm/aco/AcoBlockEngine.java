package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * <b>VÍA DE PRODUCCIÓN</b> del motor ACO. Adaptador que ejecuta {@link AlgorithmACO}
 * dentro del modelo Sa/Sc/K del cliente. Es el punto de entrada que reciben las
 * llamadas desde el front cuando se selecciona {@code motor=aco} en el endpoint
 * de planificación (ver {@code PlanificadorService.procesarBloque}).
 *
 * <p>Para cada {@link LuggageBatch} del bloque (incluyendo backlog) primero
 * intenta resolverlo con Dijkstra earliest-arrival (vía
 * {@link GreedyRepairOperator#intentarDijkstraDirecto}) o con una ruta
 * cacheada de un batch previo del mismo bloque con OD+ventana similares; solo
 * monta la corrida ACO completa cuando esos fast-paths no encuentran ruta
 * on-time. Las rutas resultantes se materializan con el mismo modelo temporal
 * del greedy ALNS (readyTime, conexiones de 10 min, capacidad global + bloque)
 * para que sean directamente comparables entre algoritmos.
 *
 * <p>El estado de capacidad se gestiona contra los mismos {@code blockFlight} y
 * {@code blockAirport} que el ALNS, vía {@link GreedyRepairOperator#aplicarAsignacionBloque}.
 *
 * <p><b>Diferencia con la vía de pruebas</b>: aquí siempre se inyecta un
 * {@link AcoBlockRouteEvaluator}, por lo que las restricciones duras del modelo
 * (flight-day, airport-day, overnight, horizonte 3d, SLA por batch) se evalúan
 * de forma idéntica al ALNS. La otra vía
 * ({@code PlanificadorService.intentarPlanificarEnvio}) NO usa evaluator y solo
 * sirve para simulación/diagnóstico.
 */
@Slf4j
@Component
public class AcoBlockEngine {

    private static final long CONNECTION_MIN = 10L;
    private static final long DEST_STORAGE_MIN = 10L;
    private static final long DAY_MIN = 1440L;
    private static final long MAX_HORIZON_MIN = 3 * DAY_MIN;

    // Cuando el presupuesto Ta restante cae por debajo de este % del total se
    // entra en modo "cumplir-Ta": skip ACO y usar solo Dijkstra (más barato).
    private static final double TA_PRESUPUESTO_AGOTADO = 0.20;

    private final PlanificadorProperties props;

    public AcoBlockEngine(PlanificadorProperties props) {
        this.props = props;
    }

    /**
     * Procesa el lote {@code batches} (datos del bloque + pendientes del backlog) con ACO.
     * Asigna ruta, departures y cumpleSLA a cada batch que encontró ruta válida.
     *
     * @return número de batches enrutados.
     */
    public int procesar(Graph graph,
                         GreedyRepairOperator enrutador,
                         List<LuggageBatch> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport) {
        return procesar(graph, enrutador, batches, blockFlight, blockAirport, null, 0L);
    }

    public int procesar(Graph graph,
                         GreedyRepairOperator enrutador,
                         List<LuggageBatch> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport,
                         Random rng) {
        return procesar(graph, enrutador, batches, blockFlight, blockAirport, rng, 0L);
    }

    /**
     * Variante con cota dura de tiempo (Ta). Si {@code tiempoLimiteMs > 0} y se
     * excede mientras se procesan batches, el bucle aborta y los restantes
     * quedan {@code clearRoute() + cumpleSLA=false} — equivalente al
     * comportamiento del {@code AlgorithmALNS.tiempoLimiteMs}, garantizando
     * Ta &lt; Sa también en el motor ACO.
     *
     * <p>Si {@code rng} es null se usa un {@link Random} sin seed (no reproducible).
     */
    public int procesar(Graph graph,
                         GreedyRepairOperator enrutador,
                         List<LuggageBatch> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport,
                         Random rng,
                         long tiempoLimiteMs) {
        if (batches == null || batches.isEmpty()) return 0;

        // Ordenar por urgencia SLA ascendente, luego por quantity descendente.
        // Los más urgentes y voluminosos se atienden primero (reducen sinRuta) y
        // agrupan batches con misma combinación OD+SLA, mejorando hit-rate de
        // la cache de rutas.
        List<LuggageBatch> ordenados = ordenarPorUrgencia(batches);

        ConfigACO cfg = configurar(ordenados.size());
        long tInicio = System.nanoTime();
        long deadlineNano = tiempoLimiteMs > 0
                ? tInicio + tiempoLimiteMs * 1_000_000L
                : Long.MAX_VALUE;
        long presupuestoTotalNano = tiempoLimiteMs > 0 ? tiempoLimiteMs * 1_000_000L : Long.MAX_VALUE;

        AlgorithmACO aco = new AlgorithmACO(graph, cfg, null);
        if (rng != null) aco.setRandom(rng);

        AcoBatchRouteCache cache = new AcoBatchRouteCache();

        int enrutados = 0;
        int procesados = 0;
        int viaDijkstra = 0;
        int viaCache = 0;
        int viaAco = 0;
        int viaFallback = 0;
        boolean abortado = false;
        boolean modoFallback = false; // true: skip ACO, solo Dijkstra

        for (LuggageBatch batch : ordenados) {
            long now = System.nanoTime();

            if (tiempoLimiteMs > 0 && now >= deadlineNano) {
                long elapsedMs = (now - tInicio) / 1_000_000L;
                log.warn("ACO abortado por presupuesto Ta: {}/{} batches procesados ({}ms >= {}ms)",
                        procesados, ordenados.size(), elapsedMs, tiempoLimiteMs);
                abortado = true;
                break;
            }

            // Si el presupuesto restante cae bajo el 20% y aún quedan batches por
            // procesar → modo "cumplir-Ta": skip ACO, solo Dijkstra.
            if (!modoFallback && tiempoLimiteMs > 0) {
                long restanteNano = deadlineNano - now;
                int restantesBatches = ordenados.size() - procesados;
                if (restantesBatches > 1
                        && (double) restanteNano / presupuestoTotalNano < TA_PRESUPUESTO_AGOTADO) {
                    modoFallback = true;
                    log.info("ACO modo cumplir-Ta activado: {}ms restante, {} batches pendientes",
                            restanteNano / 1_000_000L, restantesBatches);
                }
            }

            // Sub-deadline por batch: reparte el presupuesto restante entre los
            // batches que quedan. Aco lo respeta vía timeExpired().
            long deadlineBatchNano = deadlineNano;
            if (tiempoLimiteMs > 0) {
                int restantesBatches = ordenados.size() - procesados;
                if (restantesBatches > 0) {
                    long restanteNano = deadlineNano - now;
                    deadlineBatchNano = now + Math.max(restanteNano / restantesBatches, 1_000_000L);
                }
            }

            ResultadoBatch r = intentarBatch(aco, graph, enrutador, batch,
                    blockFlight, blockAirport, deadlineBatchNano, cache, modoFallback);
            if (r.exitoso) {
                enrutador.aplicarAsignacionBloque(batch, blockFlight, blockAirport);
                enrutados++;
                if (batch.getAssignedRoute() != null && !batch.getAssignedRoute().isEmpty()) {
                    cache.put(batch, batch.getAssignedRoute());
                }
                switch (r.via) {
                    case DIJKSTRA: viaDijkstra++; break;
                    case CACHE: viaCache++; break;
                    case ACO: viaAco++; break;
                    case FALLBACK_DIJKSTRA: viaFallback++; break;
                }
            } else {
                batch.clearRoute();
                batch.setCumpleSLA(false);
            }
            procesados++;
        }

        if (abortado) {
            for (int i = procesados; i < ordenados.size(); i++) {
                LuggageBatch b = ordenados.get(i);
                b.clearRoute();
                b.setCumpleSLA(false);
            }
        }

        long elapsedMs = (System.nanoTime() - tInicio) / 1_000_000L;
        log.info("ACO bloque batches={} enrutados={} t={}ms dijkstra={} cache={} aco={} fallback={}",
                ordenados.size(), enrutados, elapsedMs, viaDijkstra, viaCache, viaAco, viaFallback);
        return enrutados;
    }

    /**
     * Orden por (slaLimitHours asc, -quantity). Los más urgentes y voluminosos
     * primero; batches con misma combinación OD+SLA quedan contiguos para
     * mejorar el hit-rate de la cache.
     */
    private List<LuggageBatch> ordenarPorUrgencia(List<LuggageBatch> batches) {
        List<LuggageBatch> copia = new ArrayList<>(batches);
        copia.sort(Comparator
                .comparingInt(LuggageBatch::getSlaLimitHours)
                .thenComparing(Comparator.comparingInt(LuggageBatch::getQuantity).reversed()));
        return copia;
    }

    /**
     * Config dinámica del ACO según tamaño del bloque. Bloques pequeños usan
     * config "calidad" (más hormigas e iteraciones); bloques grandes "velocidad"
     * para mantener Ta acotado.
     */
    private ConfigACO configurar(int batchCount) {
        ConfigACO cfg = new ConfigACO();
        if (batchCount > 100) {
            cfg.antCount = 8;
            cfg.iterations = 20;
        } else if (batchCount > 30) {
            cfg.antCount = 10;
            cfg.iterations = 30;
        } else {
            cfg.antCount = 14;
            cfg.iterations = 40;
        }
        cfg.maxNoImprovement = 10;
        cfg.alpha = 1.0;
        cfg.beta = 2.0;
        cfg.evaporation = 0.35;
        cfg.q = 100.0;
        cfg.initialPheromone = 1.0;
        return cfg;
    }

    private enum Via { DIJKSTRA, CACHE, ACO, FALLBACK_DIJKSTRA, NINGUNA }

    private static final class ResultadoBatch {
        final boolean exitoso;
        final Via via;
        ResultadoBatch(boolean exitoso, Via via) {
            this.exitoso = exitoso;
            this.via = via;
        }
        static final ResultadoBatch FALLO = new ResultadoBatch(false, Via.NINGUNA);
    }

    /**
     * Intenta resolver el batch siguiendo el orden:
     * <ol>
     *   <li>Cache: si hay path tentativo de un batch previo con OD+ventana similar,
     *       materializarlo (revalida capacidad) y usar si pasa.</li>
     *   <li>Dijkstra-first: earliest-arrival del greedy ALNS — óptima en tiempo
     *       cuando cumple SLA.</li>
     *   <li>ACO completo: solo si Dijkstra no encontró ruta on-time. Saltado en
     *       modo cumplir-Ta.</li>
     * </ol>
     */
    private ResultadoBatch intentarBatch(AlgorithmACO aco,
                                          Graph graph,
                                          GreedyRepairOperator enrutador,
                                          LuggageBatch batch,
                                          Map<Long, Integer> blockFlight,
                                          Map<Long, Integer> blockAirport,
                                          long deadlineBatchNano,
                                          AcoBatchRouteCache cache,
                                          boolean modoFallback) {
        Node origen = graph.nodes.get(batch.getOriginCode());
        Node destino = graph.nodes.get(batch.getDestCode());
        if (origen == null || destino == null) return ResultadoBatch.FALLO;
        if (origen.idx < 0 || destino.idx < 0) return ResultadoBatch.FALLO;

        // Paso 1: cache. La ruta cacheada es solo tentativa; materializarRuta
        // re-valida capacidad contra el estado actual del bloque.
        List<Edge> rutaCacheada = cache.get(batch);
        if (rutaCacheada != null && !rutaCacheada.isEmpty()) {
            if (materializarRuta(enrutador, batch, rutaCacheada, blockFlight, blockAirport)) {
                if (batch.isCumpleSLA()) {
                    return new ResultadoBatch(true, Via.CACHE);
                }
                // Path cacheado existe pero ya no cumple SLA contra la
                // capacidad actual; descartar y caer al Dijkstra.
                batch.clearRoute();
            }
        }

        // Paso 2: Dijkstra-first (siempre). Si encuentra ruta on-time, termina;
        // si encuentra ruta tardía, guarda el flag para usarla como fallback al
        // final si ACO también falla.
        boolean dijkstraDejoRutaTardia = false;
        if (enrutador.intentarDijkstraDirecto(batch, blockFlight, blockAirport)) {
            if (batch.isCumpleSLA()) {
                return new ResultadoBatch(true, Via.DIJKSTRA);
            }
            dijkstraDejoRutaTardia = true;
        }

        // Paso 3: ACO completo (salvo modo cumplir-Ta).
        if (!modoFallback) {
            int hh = batch.getReadyTime().getHour();
            int mm = batch.getReadyTime().getMinute();
            CostFunction.EnvioContext ctx = new CostFunction.EnvioContext(
                    batch.getOriginCode(), batch.getDestCode(),
                    batch.getQuantity(), hh, mm, 0);

            aco.setEnvioContext(ctx);
            aco.setDeadlineNano(deadlineBatchNano);
            aco.setRouteEvaluator(new AcoBlockRouteEvaluator(enrutador, batch, blockFlight, blockAirport));
            aco.run(batch.getOriginCode(), batch.getDestCode());
            Ant mejor = aco.getMejorAnt();
            if (mejor != null && mejor.edgesPath != null && !mejor.edgesPath.isEmpty()
                    && batch.getDestCode().equals(mejor.path.get(mejor.path.size() - 1).code)) {
                if (materializarRuta(enrutador, batch, mejor.edgesPath, blockFlight, blockAirport)) {
                    return new ResultadoBatch(true, Via.ACO);
                }
            }
        }

        // Paso 4: Dijkstra dejó una asignación tardía (cumpleSLA=false) — la
        // aceptamos como mejor-esfuerzo si ACO no encontró ruta. Mantiene
        // paridad con el comportamiento previo (el ACO original también
        // entregaba rutas tardías con penalty grande cuando las encontraba).
        if (dijkstraDejoRutaTardia
                && batch.getAssignedRoute() != null
                && !batch.getAssignedRoute().isEmpty()) {
            return new ResultadoBatch(true, Via.FALLBACK_DIJKSTRA);
        }

        return ResultadoBatch.FALLO;
    }

    /**
     * Convierte la secuencia de aristas que produjo el ACO (o la cache) en una
     * asignación con tiempos reales (epoch‑min) respetando el modelo temporal
     * del ALNS: primer salto ≥ readyTime, escalas ≥ {@value #CONNECTION_MIN} min,
     * capacidad global + bloque, almacén destino.
     */
    private boolean materializarRuta(GreedyRepairOperator enrutador,
                                      LuggageBatch batch,
                                      List<Edge> ruta,
                                      Map<Long, Integer> blockFlight,
                                      Map<Long, Integer> blockAirport) {
        long readyMin = GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime());
        long slaMaxMin = (long) batch.getSlaLimitHours() * 60;

        List<Edge> finales = new ArrayList<>(ruta.size());
        List<Long> deps = new ArrayList<>(ruta.size());

        long earliest = readyMin;
        for (int i = 0; i < ruta.size(); i++) {
            Edge edge = ruta.get(i);
            // Pre-filter por capacidad física del vuelo: si la capacidad nominal
            // no alcanza, esta ruta no es factible para este batch — descartar
            // sin entrar al cómputo de capacidadRestante.
            if (edge.capacity > 0 && edge.capacity < batch.getQuantity()) return false;

            long puedoSalirDesde = (i == 0) ? earliest : earliest + CONNECTION_MIN;
            long actualDep = enrutador.calcularProximaSalida(edge.depMinuteOfDay, puedoSalirDesde);
            long actualArr = actualDep + edge.durationMinutes;

            if (actualArr - readyMin > MAX_HORIZON_MIN) return false;
            if (enrutador.capacidadRestante(edge, actualDep, blockFlight) < batch.getQuantity()) return false;
            if (edge.to != null && edge.to.capacity > 0) {
                if (enrutador.capacidadAlmacen(edge.to, actualArr, blockAirport) < batch.getQuantity()) return false;
                if (!batch.getDestCode().equals(edge.to.code)
                        && enrutador.capacidadAlmacen(edge.to, actualArr + DAY_MIN, blockAirport) < batch.getQuantity()) {
                    return false;
                }
            }

            finales.add(edge);
            deps.add(actualDep);
            earliest = actualArr;
        }

        long llegadaFinal = deps.get(deps.size() - 1) + finales.get(finales.size() - 1).durationMinutes;
        long transitMin = (llegadaFinal + DEST_STORAGE_MIN) - readyMin;
        boolean cumpleSLA = transitMin <= slaMaxMin;

        batch.setAssignedRoute(finales);
        batch.setAssignedDepartures(deps);
        batch.setCumpleSLA(cumpleSLA);
        return true;
    }
}
