package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Adaptador que ejecuta {@link AlgorithmACO} dentro del modelo Sa/Sc/K del cliente.
 *
 * <p>Para cada {@link LuggageBatch} del bloque (incluyendo backlog) lanza una
 * corrida ACO que produce una secuencia de aeropuertos. Después calcula las
 * salidas reales (epoch‑min) usando el mismo modelo temporal del greedy ALNS
 * — {@code readyTime}, tiempo mínimo de escala, capacidad global + bloque —
 * para que las rutas sean directamente comparables entre algoritmos.
 *
 * <p>El estado de capacidad se gestiona contra los mismos {@code blockFlight} y
 * {@code blockAirport} que el ALNS, vía {@link GreedyRepairOperator#aplicarAsignacionBloque}.
 */
@Slf4j
@Component
public class AcoBlockEngine {

    private static final long CONNECTION_MIN = 10L;
    private static final long DEST_STORAGE_MIN = 10L;
    private static final long DAY_MIN = 1440L;
    private static final long MAX_HORIZON_MIN = 3 * DAY_MIN;

    private final PlanificadorProperties props;

    // Pheromone reset incremental: guarda las aristas tocadas en el batch previo
    // para resetear solo esas (~10-30) en vez de todas (2866) por batch.
    private final Set<Integer> touchedEdgesPreviousBatch = new HashSet<>();

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

        ConfigACO cfg = configurar();
        int enrutados = 0;
        long tInicio = System.nanoTime();
        boolean abortado = false;
        int procesados = 0;

        for (LuggageBatch batch : batches) {
            // Cota dura de tiempo: si Ta se excede, abortar el bucle.
            if (tiempoLimiteMs > 0) {
                long elapsedMs = (System.nanoTime() - tInicio) / 1_000_000L;
                if (elapsedMs > tiempoLimiteMs) {
                    log.warn("ACO abortado por presupuesto Ta: {}/{} batches procesados ({}ms > {}ms)",
                            procesados, batches.size(), elapsedMs, tiempoLimiteMs);
                    abortado = true;
                    break;
                }
            }

            if (intentarBatch(graph, enrutador, batch, blockFlight, blockAirport, cfg, rng)) {
                enrutador.aplicarAsignacionBloque(batch, blockFlight, blockAirport);
                enrutados++;
            } else {
                batch.clearRoute();
                batch.setCumpleSLA(false);
            }
            procesados++;
        }

        // Batches que no alcanzaron a procesarse → marcar sinRuta para mantener
        // el invariante "todo batch del bloque tiene su asignación final decidida".
        if (abortado) {
            for (int i = procesados; i < batches.size(); i++) {
                LuggageBatch b = batches.get(i);
                b.clearRoute();
                b.setCumpleSLA(false);
            }
        }
        return enrutados;
    }

    private ConfigACO configurar() {
        ConfigACO cfg = new ConfigACO();
        // Presupuesto Ta del modelo Sa/Sc/K es ajustado: cada batch debe terminar
        // en pocos ms para que el bloque entero (cientos de batches) quepa en Ta.
        // Con maxNoImprovement el ACO corta apenas converge a una ruta válida —
        // típico para rutas directas/cortas que dominan el dataset.
        cfg.antCount = 8;
        cfg.iterations = 20;
        cfg.maxNoImprovement = 5;
        cfg.alpha = 1.0;
        cfg.beta = 2.0;
        cfg.evaporation = 0.5;
        cfg.q = 100.0;
        cfg.initialPheromone = 1.0;
        return cfg;
    }

    private boolean intentarBatch(Graph graph,
                                   GreedyRepairOperator enrutador,
                                   LuggageBatch batch,
                                   Map<Long, Integer> blockFlight,
                                   Map<Long, Integer> blockAirport,
                                   ConfigACO cfg,
                                   Random rng) {
        Node origen = graph.nodes.get(batch.getOriginCode());
        Node destino = graph.nodes.get(batch.getDestCode());
        if (origen == null || destino == null) return false;
        if (origen.idx < 0 || destino.idx < 0) return false;

        // === PHEROMONE RESET INCREMENTAL ===
        // Resetear solo las aristas tocadas en el batch previo (~10-30 en vez de 2866)
        for (Integer edgeIdx : touchedEdgesPreviousBatch) {
            for (Edge e : graph.edges) {
                if (e.idx == edgeIdx) {
                    e.pheromone = cfg.initialPheromone;
                    break;
                }
            }
        }

        int hh = batch.getReadyTime().getHour();
        int mm = batch.getReadyTime().getMinute();
        CostFunction.EnvioContext ctx = new CostFunction.EnvioContext(
                batch.getOriginCode(), batch.getDestCode(),
                batch.getQuantity(), hh, mm);

        AlgorithmACO aco = new AlgorithmACO(graph, cfg, ctx);
        if (rng != null) aco.setRandom(rng);
        aco.run(batch.getOriginCode(), batch.getDestCode());
        Ant mejor = aco.getMejorAnt();
        if (mejor == null || mejor.edgesPath == null || mejor.edgesPath.isEmpty()) return false;
        if (!batch.getDestCode().equals(mejor.path.get(mejor.path.size() - 1).code)) return false;

        // Guardar aristas tocadas para el siguiente batch (pheromone reset incremental)
        touchedEdgesPreviousBatch.clear();
        for (Edge e : mejor.edgesPath) {
            if (e.idx >= 0) touchedEdgesPreviousBatch.add(e.idx);
        }

        return materializarRuta(enrutador, batch, mejor.edgesPath, blockFlight, blockAirport);
    }

    /**
     * Convierte la secuencia de aristas que produjo el ACO en una asignación con
     * tiempos reales (epoch‑min) respetando el modelo temporal del ALNS:
     * primer salto ≥ readyTime, escalas ≥ {@value #CONNECTION_MIN} min, capacidad
     * global + bloque, almacén destino.
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
            // Primer tramo: sin tiempo mínimo de espera; tramos siguientes: CONNECTION_MIN.
            long puedoSalirDesde = (i == 0) ? earliest : earliest + CONNECTION_MIN;
            long actualDep = enrutador.calcularProximaSalida(edge.depMinuteOfDay, puedoSalirDesde);
            long actualArr = actualDep + edge.durationMinutes;

            // Horizonte máximo: 3 días desde readyTime.
            if (actualArr - readyMin > MAX_HORIZON_MIN) return false;
            // Capacidad del vuelo (global + bloque).
            if (enrutador.capacidadRestante(edge, actualDep, blockFlight) < batch.getQuantity()) return false;
            // Capacidad de almacén del aeropuerto destino del tramo.
            if (edge.to != null && edge.to.capacity > 0) {
                if (enrutador.capacidadAlmacen(edge.to, actualArr, blockAirport) < batch.getQuantity()) return false;
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
