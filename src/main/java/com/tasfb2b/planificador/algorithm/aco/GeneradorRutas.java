package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator.RouteCandidate;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.algorithm.grafo.Edge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provee las rutas candidatas de cada envío llamando al {@link GreedyRepairOperator} (Dijkstra
 * multicriterio) y las reutiliza vía una caché de paths por bloque. Es el "hijo Dijkstra" del ACO.
 */
final class GeneradorRutas {

    static final long CACHE_BUCKET_MIN = 60L;
    private static final int MAX_CACHE_PATHS_PER_KEY = 8;

    private final GreedyRepairOperator enrutador;
    private final RouteCandidateCache cache = new RouteCandidateCache();
    private final EstadisticasBusqueda stats;

    GeneradorRutas(GreedyRepairOperator enrutador, EstadisticasBusqueda stats) {
        this.enrutador = enrutador;
        this.stats = stats;
    }

    List<RouteCandidate> obtenerRutas(LuggageBatch batch,
                                      Map<Long, Integer> simFlight,
                                      Map<Long, Integer> simAirport,
                                      int maxCandidatos) {
        List<RouteCandidate> rutas = new ArrayList<>(maxCandidatos);
        Set<String> firmas = new HashSet<>();

        List<List<Edge>> cachedPaths = cache.get(batch);
        if (cachedPaths != null && !cachedPaths.isEmpty()) {
            boolean hit = false;
            for (List<Edge> path : cachedPaths) {
                RouteCandidate candidate = enrutador.materializarRutaCandidata(batch, path, simFlight, simAirport);
                if (candidate == null) {
                    stats.cacheRejects++;
                    continue;
                }
                if (firmas.add(candidate.signature())) {
                    rutas.add(candidate);
                    hit = true;
                }
                if (rutas.size() >= maxCandidatos) break;
            }
            if (hit) stats.cacheHits++;
        }

        if (rutas.size() < maxCandidatos) {
            stats.dijkstraCalls++;
            List<RouteCandidate> generadas = enrutador.generarCandidatosRuta(
                    batch, simFlight, simAirport, maxCandidatos);
            cache.put(batch, generadas);
            for (RouteCandidate candidate : generadas) {
                if (firmas.add(candidate.signature())) {
                    rutas.add(candidate);
                }
            }
        }

        rutas.sort(Heuristica::compararRutaBase);
        if (rutas.size() <= maxCandidatos) return rutas;
        return new ArrayList<>(rutas.subList(0, maxCandidatos));
    }

    Set<Long> clavesDeRutas(LuggageBatch batch, List<RouteCandidate> rutas) {
        Set<Long> keys = new HashSet<>(rutas.size() * 6);
        for (RouteCandidate ruta : rutas) {
            keys.addAll(enrutador.clavesOcupadas(ruta, batch));
        }
        return keys;
    }

    private static final class RouteCandidateCache {
        private final Map<CacheKey, CachedPaths> cache = new HashMap<>();

        List<List<Edge>> get(LuggageBatch batch) {
            CacheKey key = CacheKey.from(batch);
            if (key == null) return List.of();
            CachedPaths entry = cache.get(key);
            return entry == null ? List.of() : entry.paths;
        }

        void put(LuggageBatch batch, List<RouteCandidate> candidates) {
            if (candidates == null || candidates.isEmpty()) return;
            CacheKey key = CacheKey.from(batch);
            if (key == null) return;
            CachedPaths entry = cache.computeIfAbsent(key, k -> new CachedPaths());
            for (RouteCandidate candidate : candidates) {
                if (candidate.getEdges().isEmpty()) continue;
                String signature = pathSignature(candidate.getEdges());
                if (!entry.signatures.add(signature)) continue;
                entry.paths.add(List.copyOf(candidate.getEdges()));
                if (entry.paths.size() >= MAX_CACHE_PATHS_PER_KEY) break;
            }
        }

        private static String pathSignature(List<Edge> path) {
            StringBuilder sb = new StringBuilder(path.size() * 6);
            for (Edge edge : path) {
                sb.append(edge.idx).append(';');
            }
            return sb.toString();
        }
    }

    private static final class CachedPaths {
        final List<List<Edge>> paths = new ArrayList<>();
        final Set<String> signatures = new HashSet<>();
    }

    private static final class CacheKey {
        final String origin;
        final String dest;
        final long readyBucket;
        final int slaHours;
        final int quantity;

        private CacheKey(String origin, String dest, long readyBucket, int slaHours, int quantity) {
            this.origin = origin;
            this.dest = dest;
            this.readyBucket = readyBucket;
            this.slaHours = slaHours;
            this.quantity = quantity;
        }

        static CacheKey from(LuggageBatch batch) {
            if (batch == null || batch.getReadyTime() == null) return null;
            long readyBucket = GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime()) / CACHE_BUCKET_MIN;
            return new CacheKey(batch.getOriginCode(), batch.getDestCode(), readyBucket,
                    batch.getSlaLimitHours(), batch.getQuantity());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey other)) return false;
            return readyBucket == other.readyBucket
                    && slaHours == other.slaHours
                    && quantity == other.quantity
                    && Objects.equals(origin, other.origin)
                    && Objects.equals(dest, other.dest);
        }

        @Override
        public int hashCode() {
            return Objects.hash(origin, dest, readyBucket, slaHours, quantity);
        }
    }
}
