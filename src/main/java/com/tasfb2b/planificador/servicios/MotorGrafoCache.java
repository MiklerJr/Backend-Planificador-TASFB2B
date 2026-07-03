package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;


@Slf4j
@Component
public class MotorGrafoCache {

    private volatile Grafo grafo;
    private final Map<Long, List<int[]>> skeletonCache = new ConcurrentHashMap<>();

    public Grafo obtenerGrafo(Supplier<Grafo> constructor) {
        Grafo g = grafo;
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
