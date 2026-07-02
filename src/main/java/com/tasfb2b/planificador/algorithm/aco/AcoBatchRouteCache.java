package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.algorithm.grafo.Edge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AcoBatchRouteCache {

    private static final long BUCKET_MIN = 60L;

    private final Map<Long, List<Edge>> cache = new HashMap<>();

    public List<Edge> get(LuggageBatch batch) {
        Long key = encodeKey(batch);
        if (key == null) return null;
        return cache.get(key);
    }

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
        int h = code.hashCode();
        return (h & 0xFFFF) ^ ((h >>> 16) & 0xFFFF);
    }
}
