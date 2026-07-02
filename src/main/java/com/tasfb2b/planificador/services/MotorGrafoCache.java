package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.grafo.Graph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;


@Slf4j
@Component
public class MotorGrafoCache {

    private volatile Graph grafo;
    private final Map<Long, List<int[]>> skeletonCache = new ConcurrentHashMap<>();

    public Graph obtenerGrafo(Supplier<Graph> constructor) {
        Graph g = grafo;
        if (g == null) {
            synchronized (this) {
                g = grafo;
                if (g == null) {
                    g = constructor.get();
                    grafo = g;
                }
            }
        }
        return g;
    }

    public Map<Long, List<int[]>> skeletonCache() {
        return skeletonCache;
    }

    public synchronized void invalidar() {
        grafo = null;
        skeletonCache.clear();
        log.info("Caché de grafo y esqueletos invalidada (recarga de dataset).");
    }
}
