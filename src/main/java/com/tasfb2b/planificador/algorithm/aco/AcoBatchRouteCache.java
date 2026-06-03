package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache local a una invocación de {@code AcoBlockEngine.procesar(...)} que
 * memoriza el path tentativo (lista de {@link Edge}) encontrado para una
 * combinación (origen, destino, ventana de tiempo, SLA). En bloques con muchos
 * batches similares (mismo OD y misma hora aproximada) evita recomputar el
 * Dijkstra o la corrida ACO; el caller debe re-validar capacidad antes de
 * aplicar la ruta, porque la ocupación del bloque cambió tras cada commit.
 *
 * <p>NO se reutiliza entre bloques: la capacidad disponible varía por bloque y
 * un path válido en uno puede ser inviable en el siguiente.
 *
 * <p>La cache no es thread-safe — está pensada para uso secuencial dentro de un
 * único hilo del motor ACO.
 *
 * <h3>Esquema de la clave</h3>
 * <pre>
 *   bits 0-15  : originIdx (16 bits, hasta 65 k aeropuertos)
 *   bits 16-31 : destIdx   (16 bits)
 *   bits 32-55 : readyBucket = readyMin / BUCKET_MIN (24 bits ≈ 26 años en horas)
 *   bits 56-63 : slaLimitHours (8 bits, hasta 255 h)
 * </pre>
 */
public class AcoBatchRouteCache {

    /** Tamaño del bucket de readyTime, en minutos. 60 min agrupa batches de la misma hora. */
    private static final long BUCKET_MIN = 60L;

    private final Map<Long, List<Edge>> cache = new HashMap<>();

    /**
     * Devuelve un path cacheado para la combinación del batch, o {@code null}
     * si no hay hit. El caller DEBE revalidar capacidad antes de aplicar el
     * path (la cache no garantiza factibilidad respecto al estado actual del
     * bloque).
     */
    public List<Edge> get(LuggageBatch batch) {
        Long key = encodeKey(batch);
        if (key == null) return null;
        return cache.get(key);
    }

    /**
     * Guarda un path tentativo. Solo se llama cuando el path acabó de ser
     * materializado con éxito (capacidad disponible y SLA cumplido).
     */
    public void put(LuggageBatch batch, List<Edge> path) {
        if (path == null || path.isEmpty()) return;
        Long key = encodeKey(batch);
        if (key == null) return;
        cache.put(key, path);
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    private static Long encodeKey(LuggageBatch batch) {
        if (batch == null || batch.getReadyTime() == null) return null;
        // Para resolver originIdx/destIdx el caller resuelve los Nodes; aquí
        // codificamos directamente desde los códigos ICAO con un hash entero
        // estable. Pero como Node.idx ya está asignado por GreedyRepairOperator,
        // preferimos delegarlo a quien llama. Esta variante usa hashes seguros
        // a partir del String origen/destino para no acoplar la cache al Graph.
        int o = stableIdx(batch.getOriginCode());
        int d = stableIdx(batch.getDestCode());
        if (o < 0 || d < 0) return null;
        long readyMin = GreedyRepairOperator.toEpochMinPublic(batch.getReadyTime());
        long bucket = (readyMin / BUCKET_MIN) & 0xFFFFFFL;
        long sla = batch.getSlaLimitHours() & 0xFFL;
        return (((long) o) & 0xFFFFL)
             | ((((long) d) & 0xFFFFL) << 16)
             | (bucket << 32)
             | (sla << 56);
    }

    private static int stableIdx(String code) {
        if (code == null || code.isEmpty()) return -1;
        // Hash estable de 16 bits para ICAO de 4 letras: cabe perfectamente.
        // Colisiones posibles, pero el caller verifica el path real al
        // materializarlo, así que una colisión solo causa cache-miss benigno
        // (cae al Dijkstra/ACO sin afectar correctitud).
        int h = code.hashCode();
        return (h & 0xFFFF) ^ ((h >>> 16) & 0xFFFF);
    }
}
