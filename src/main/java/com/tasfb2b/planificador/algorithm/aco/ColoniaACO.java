package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator.RouteCandidate;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.algorithm.grafo.Graph;
import com.tasfb2b.planificador.config.PlanificadorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Optimización por Colonia de Hormigas (ACO) por bloque: el "ACO padre" que orquesta una colonia
 * de hormigas ({@link ConstructorHormiga}) sobre un rastro de feromonas ({@link RastroFeromonas}),
 * una heurística ({@link Heuristica}), una ruleta de selección ({@link RuletaSeleccion}) y un
 * generador de rutas Dijkstra ({@link GeneradorRutas}). Cada bloque: siembra una solución greedy
 * determinista, itera colonias de hormigas evaporando y depositando feromona, y aplica la mejor
 * solución encontrada al enrutador real.
 */
@Slf4j
@Component
public class ColoniaACO {

    private static final double BASE_PHEROMONE_BOOST = 2.0;
    private static final double RESERVA_BASE = 0.15;              // colchón en vuelos para flexibles
    private static final double UMBRAL_CONGESTION_DEFER = 2.0;    // ruta "cara" en congestión
    private static final long   MARGEN_DEFER_MIN = 1440L;         // solo diferir si slack > 24h (urgentes nunca)
    private static final int    GROUP_ROUTE_CANDIDATES = 5;       // más candidatos por grupo → más diversidad de congestión
    private static final boolean ENABLE_J3_DEFER = false;

    private final PlanificadorProperties props;
    private int diagSeq = 0;   // N1: secuencia de bloques para throttlear el diagnóstico a INFO

    public ColoniaACO(PlanificadorProperties props) {
        this.props = props;
    }

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

    public int procesar(Graph graph,
                         GreedyRepairOperator enrutador,
                         List<LuggageBatch> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport,
                         Random rng,
                         long tiempoLimiteMs) {
        if (batches == null || batches.isEmpty()) return 0;

        Random random = rng != null ? rng : new Random();
        ConfiguracionACO cfg = configurar(batches.size());
        List<LuggageBatch> base = ordenarPorUrgencia(batches);

        Map<LuggageBatch, String> batchKeys = new IdentityHashMap<>(base.size() * 2);
        for (LuggageBatch b : base) batchKeys.put(b, RastroFeromonas.claveBatch(b));

        long inicio = System.nanoTime();
        long deadline = tiempoLimiteMs > 0
                ? inicio + tiempoLimiteMs * 1_000_000L
                : Long.MAX_VALUE;

        EstadisticasBusqueda stats = new EstadisticasBusqueda();
        RastroFeromonas feromonas = new RastroFeromonas(cfg);
        GeneradorRutas generador = new GeneradorRutas(enrutador, stats);
        Heuristica heuristica = new Heuristica();
        RuletaSeleccion ruleta = new RuletaSeleccion(feromonas, heuristica, random);
        ConstructorHormiga hormiga = new ConstructorHormiga(
                enrutador, generador, heuristica, ruleta, feromonas, random);

        SolucionBloque mejor = null;
        int sinMejora = 0;
        int solucionesEvaluadas = 0;

        Set<LuggageBatch> diferidos = Collections.newSetFromMap(new IdentityHashMap<>());
        SolucionBloque baseDeterministica = construirSolucionBase(
                enrutador, generador, heuristica, base, blockFlight, blockAirport, deadline, batchKeys, diferidos);
        if (baseDeterministica != null) {
            mejor = baseDeterministica;
            solucionesEvaluadas++;
            if (baseDeterministica.enrutados == base.size()) stats.solucionesCompletas++;
            feromonas.depositar(baseDeterministica, cfg.q * BASE_PHEROMONE_BOOST);
        }

        List<LuggageBatch> baseAnts = base;
        if (!diferidos.isEmpty()) {
            baseAnts = new ArrayList<>(base.size() - diferidos.size());
            for (LuggageBatch b : base) if (!diferidos.contains(b)) baseAnts.add(b);
        }

        for (int iter = 0; iter < cfg.iterations && System.nanoTime() < deadline; iter++) {
            boolean huboMejora = false;

            for (int ant = 0; ant < cfg.antCount && System.nanoTime() < deadline; ant++) {
                SolucionBloque candidata = hormiga.construir(
                        baseAnts, blockFlight, blockAirport, batchKeys, deadline);
                solucionesEvaluadas++;
                if (candidata.enrutados == base.size()) stats.solucionesCompletas++;
                if (mejorQue(candidata, mejor)) {
                    mejor = candidata;
                    huboMejora = true;
                }
            }

            feromonas.evaporar(cfg.evaporation);
            if (mejor != null) feromonas.depositar(mejor, cfg.q);

            sinMejora = huboMejora ? 0 : sinMejora + 1;
            if (cfg.maxNoImprovement > 0 && sinMejora >= cfg.maxNoImprovement) break;
        }

        for (LuggageBatch b : batches) {
            b.clearRoute();
            b.setCumpleSLA(false);
        }

        int enrutados = 0;
        int onTime = 0;
        if (mejor != null) {
            for (Asignacion asignacion : mejor.asignaciones) {
                if (!asignacion.route.isCumpleSLA()) continue;
                enrutador.aplicarCandidatoRuta(asignacion.batch, asignacion.route);
                enrutador.aplicarCandidatoBloque(asignacion.batch, asignacion.route, blockFlight, blockAirport);
                enrutados++;
                onTime++;
            }
        }

        long elapsedMs = (System.nanoTime() - inicio) / 1_000_000L;
        int sinRuta = batches.size() - enrutados;
        if (log.isInfoEnabled() && ++diagSeq % 50 == 0) {
            log.info("ACO padre bloque batches={} enrutados={} onTime={} sinRuta={} soluciones={} completas={} cacheHits={} cacheRejects={} dijkstraCalls={} t={}ms",
                    batches.size(), enrutados, onTime, sinRuta, solucionesEvaluadas, stats.solucionesCompletas,
                    stats.cacheHits, stats.cacheRejects, stats.dijkstraCalls, elapsedMs);
        } else if (log.isDebugEnabled()) {
            log.debug("ACO padre bloque batches={} enrutados={} onTime={} sinRuta={} soluciones={} completas={} cacheHits={} cacheRejects={} dijkstraCalls={} t={}ms",
                    batches.size(), enrutados, onTime, sinRuta, solucionesEvaluadas, stats.solucionesCompletas,
                    stats.cacheHits, stats.cacheRejects, stats.dijkstraCalls, elapsedMs);
        }
        return enrutados;
    }

    List<LuggageBatch> ordenarPorUrgencia(List<LuggageBatch> batches) {
        List<LuggageBatch> copia = new ArrayList<>(batches);
        copia.sort(Comparator
                .comparingLong(ColoniaACO::deadlineEpochMin)
                .thenComparing(Comparator.comparingInt(LuggageBatch::getQuantity).reversed())
                .thenComparing(LuggageBatch::getOriginCode)
                .thenComparing(LuggageBatch::getDestCode));
        return copia;
    }

    private static long deadlineEpochMin(LuggageBatch batch) {
        return GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime())
                + (long) batch.getSlaLimitHours() * 60L;
    }

    private ConfiguracionACO configurar(int batchCount) {
        ConfiguracionACO cfg = new ConfiguracionACO();
        if (batchCount > 100) {
            cfg.antCount = 8;
            cfg.iterations = 54;
        } else if (batchCount > 30) {
            cfg.antCount = 10;
            cfg.iterations = 84;
        } else {
            cfg.antCount = 14;
            cfg.iterations = 108;
        }
        cfg.maxNoImprovement = 8;
        cfg.alpha = 1.0;
        cfg.beta = 2.0;
        cfg.evaporation = 0.30;
        cfg.q = 100.0;
        cfg.initialPheromone = 1.0;
        return cfg;
    }

    private SolucionBloque construirSolucionBase(GreedyRepairOperator enrutador,
                                                 GeneradorRutas generador,
                                                 Heuristica heuristica,
                                                 List<LuggageBatch> base,
                                                 Map<Long, Integer> blockFlight,
                                                 Map<Long, Integer> blockAirport,
                                                 long deadline,
                                                 Map<LuggageBatch, String> batchKeys,
                                                 Set<LuggageBatch> diferidos) {
        Map<Long, Integer> simFlight = new HashMap<>(blockFlight);
        Map<Long, Integer> simAirport = new HashMap<>(blockAirport);
        List<Asignacion> asignaciones = new ArrayList<>();

        Map<String, List<LuggageBatch>> grupos = new LinkedHashMap<>();
        for (LuggageBatch batch : base) {
            grupos.computeIfAbsent(groupKey(batch), k -> new ArrayList<>()).add(batch);
        }

        for (List<LuggageBatch> grupo : grupos.values()) {
            if (System.nanoTime() >= deadline) break;

            LuggageBatch rep = grupo.get(0);
            for (LuggageBatch b : grupo) {
                if (b.getQuantity() > rep.getQuantity()) rep = b;
            }
            List<RouteCandidate> rutasGrupo = generador.obtenerRutas(
                    rep, simFlight, simAirport, GROUP_ROUTE_CANDIDATES);

            for (LuggageBatch batch : grupo) {
                if (System.nanoTime() >= deadline) break;

                RouteCandidate elegida = seleccionarRuta(enrutador, heuristica, batch, rutasGrupo, simFlight, simAirport);
                if (elegida == null) {
                    // Ninguna ruta del grupo le sirve: recomputar para este envío.
                    List<RouteCandidate> propias = generador.obtenerRutas(
                            batch, simFlight, simAirport, GROUP_ROUTE_CANDIDATES);
                    elegida = seleccionarRuta(enrutador, heuristica, batch, propias, simFlight, simAirport);
                }
                if (elegida == null) continue;   // sinRuta → se difiere al backlog

                if (ENABLE_J3_DEFER
                        && elegida.getSlackMin() > MARGEN_DEFER_MIN
                        && elegida.getScarcityCost() > UMBRAL_CONGESTION_DEFER) {
                    diferidos.add(batch);
                    continue;
                }

                String bKey = batchKeys.get(batch);
                enrutador.aplicarCandidatoBloque(batch, elegida, simFlight, simAirport);
                asignaciones.add(new Asignacion(batch, elegida, RastroFeromonas.claveFeromona(bKey, elegida), bKey));
            }
        }

        return new SolucionBloque(asignaciones, base.size());
    }

    private RouteCandidate seleccionarRuta(GreedyRepairOperator enrutador,
                                           Heuristica heuristica,
                                           LuggageBatch batch,
                                           List<RouteCandidate> candidatos,
                                           Map<Long, Integer> simFlight,
                                           Map<Long, Integer> simAirport) {
        double reservaAlmacen = props.getStorageAware().getReservaAlmacenBase();
        RouteCandidate best = mejorPorCosto(enrutador, heuristica, batch, candidatos, simFlight, simAirport,
                RESERVA_BASE, reservaAlmacen);
        if (best == null && (RESERVA_BASE > 0.0 || reservaAlmacen > 0.0)) {
            best = mejorPorCosto(enrutador, heuristica, batch, candidatos, simFlight, simAirport, 0.0, 0.0);
        }
        return best;
    }

    private RouteCandidate mejorPorCosto(GreedyRepairOperator enrutador,
                                         Heuristica heuristica,
                                         LuggageBatch batch,
                                         List<RouteCandidate> candidatos,
                                         Map<Long, Integer> simFlight,
                                         Map<Long, Integer> simAirport,
                                         double reservaBase,
                                         double reservaAlmacenBase) {
        RouteCandidate best = null;
        double bestCost = Double.MAX_VALUE;
        for (RouteCandidate r : candidatos) {
            if (!r.isCumpleSLA()) continue;                                  // F1
            if (!enrutador.rutaSirveParaBatch(r, batch, simFlight, simAirport,
                    reservaBase, reservaAlmacenBase)) continue;
            double c = heuristica.costoSeleccion(batch, r);
            if (c < bestCost) { bestCost = c; best = r; }
        }
        return best;
    }

    private String groupKey(LuggageBatch batch) {
        long readyBucket = batch.getReadyTime() == null
                ? 0L
                : GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime()) / GeneradorRutas.CACHE_BUCKET_MIN;
        return batch.getOriginCode() + '|' + batch.getDestCode()
                + '|' + readyBucket + '|' + batch.getSlaLimitHours();
    }

    private boolean mejorQue(SolucionBloque candidata, SolucionBloque actual) {
        if (candidata == null) return false;
        if (actual == null) return true;
        if (candidata.cumpleSla != actual.cumpleSla) {
            return candidata.cumpleSla > actual.cumpleSla;
        }
        if (candidata.enrutados != actual.enrutados) {
            return candidata.enrutados > actual.enrutados;
        }
        if (candidata.tardados != actual.tardados) {
            return candidata.tardados < actual.tardados;
        }
        return candidata.cost < actual.cost;
    }
}
