package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        return procesar(graph, enrutador, batches, blockFlight, blockAirport, null);
    }

    /**
     * Variante reproducible: usa el {@link Random} provisto para todas las
     * decisiones aleatorias de cada {@link AlgorithmACO}. Si {@code rng} es
     * null, se usa un {@link Random} sin seed (no reproducible).
     */
    public int procesar(Graph graph,
                         GreedyRepairOperator enrutador,
                         List<LuggageBatch> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport,
                         Random rng) {
        if (batches == null || batches.isEmpty()) return 0;

        ConfigACO cfg = configurar();
        int enrutados = 0;
        for (LuggageBatch batch : batches) {
            if (intentarBatch(graph, enrutador, batch, blockFlight, blockAirport, cfg, rng)) {
                enrutador.aplicarAsignacionBloque(batch, blockFlight, blockAirport);
                enrutados++;
            } else {
                batch.clearRoute();
                batch.setCumpleSLA(false);
            }
        }
        return enrutados;
    }

    private ConfigACO configurar() {
        ConfigACO cfg = new ConfigACO();
        cfg.antCount = Math.max(1, props.getAlns().getIteracionesBase());
        cfg.iterations = Math.max(5, props.getAlns().getIteracionesBase() * 3);
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

        // Resetear feromonas para que cada batch empiece sin sesgo del batch anterior.
        for (Edge e : graph.edges) e.pheromone = cfg.initialPheromone;

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
