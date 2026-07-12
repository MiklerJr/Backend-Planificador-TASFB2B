package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class GeneradorRutas {

    static final long CACHE_BUCKET_MIN = 60L;
    private static final int MAX_RUTAS_CACHE_POR_CLAVE = 8;

    private final OperadorReparacionVoraz enrutador;
    private final CacheCandidatosRuta cache = new CacheCandidatosRuta();
    private final EstadisticasBusqueda stats;

    GeneradorRutas(OperadorReparacionVoraz enrutador, EstadisticasBusqueda stats) {
        this.enrutador = enrutador;
        this.stats = stats;
    }

    List<RutaCandidata> obtenerRutas(LoteEnvio batch,
                                      Map<Long, Integer> simFlight,
                                      Map<Long, Integer> simAirport,
                                      int maxCandidatos) {
        List<RutaCandidata> rutas = new ArrayList<>(maxCandidatos);
        Set<String> firmas = new HashSet<>();

        List<List<Arista>> cachedPaths = cache.get(batch);
        if (cachedPaths != null && !cachedPaths.isEmpty()) {
            boolean hit = false;
            for (List<Arista> path : cachedPaths) {
                RutaCandidata candidate = enrutador.materializarRutaCandidata(batch, path, simFlight, simAirport);
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
            List<RutaCandidata> generadas = enrutador.generarCandidatosRuta(
                    batch, simFlight, simAirport, maxCandidatos);
            cache.put(batch, generadas);
            for (RutaCandidata candidate : generadas) {
                if (firmas.add(candidate.signature())) {
                    rutas.add(candidate);
                }
            }
        }

        rutas.sort(Heuristica::compararRutaBase);
        if (rutas.size() <= maxCandidatos) return rutas;
        return new ArrayList<>(rutas.subList(0, maxCandidatos));
    }

    Set<Long> clavesDeRutas(LoteEnvio batch, List<RutaCandidata> rutas) {
        Set<Long> keys = new HashSet<>(rutas.size() * 6);
        for (RutaCandidata ruta : rutas) {
            keys.addAll(enrutador.clavesOcupadas(ruta, batch));
        }
        return keys;
    }

    private static final class CacheCandidatosRuta {
        private final Map<ClaveCache, RutasCacheadas> cache = new HashMap<>();

        List<List<Arista>> get(LoteEnvio batch) {
            ClaveCache key = ClaveCache.desde(batch);
            if (key == null) return List.of();
            RutasCacheadas entry = cache.get(key);
            return entry == null ? List.of() : entry.paths;
        }

        void put(LoteEnvio batch, List<RutaCandidata> candidates) {
            if (candidates == null || candidates.isEmpty()) return;
            ClaveCache key = ClaveCache.desde(batch);
            if (key == null) return;
            RutasCacheadas entry = cache.computeIfAbsent(key, k -> new RutasCacheadas());
            for (RutaCandidata candidate : candidates) {
                if (candidate.getAristas().isEmpty()) continue;
                String signature = pathSignature(candidate.getAristas());
                if (!entry.signatures.add(signature)) continue;
                entry.paths.add(List.copyOf(candidate.getAristas()));
                if (entry.paths.size() >= MAX_RUTAS_CACHE_POR_CLAVE) break;
            }
        }

        private static String pathSignature(List<Arista> path) {
            StringBuilder sb = new StringBuilder(path.size() * 6);
            for (Arista edge : path) {
                sb.append(edge.indice).append(';');
            }
            return sb.toString();
        }
    }

    private static final class RutasCacheadas {
        final List<List<Arista>> paths = new ArrayList<>();
        final Set<String> signatures = new HashSet<>();
    }

    private static final class ClaveCache {
        final String origin;
        final String dest;
        final long readyBucket;
        final int slaHours;
        final int quantity;

        private ClaveCache(String origin, String dest, long readyBucket, int slaHours, int quantity) {
            this.origin = origin;
            this.dest = dest;
            this.readyBucket = readyBucket;
            this.slaHours = slaHours;
            this.quantity = quantity;
        }

        static ClaveCache desde(LoteEnvio batch) {
            if (batch == null || batch.getTiempoListo() == null) return null;
            long readyBucket = OperadorReparacionVoraz.aMinutoEpochPublico(batch.getTiempoListo()) / CACHE_BUCKET_MIN;
            return new ClaveCache(batch.getCodigoOrigen(), batch.getCodigoDestino(), readyBucket,
                    batch.getHorasLimiteSla(), batch.getCantidad());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ClaveCache other)) return false;
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
