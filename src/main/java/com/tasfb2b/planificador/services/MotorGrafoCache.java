package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.grafo.Graph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Cachés del motor reutilizables ENTRE simulaciones (no por job), para recortar la latencia de
 * arranque. Las simulaciones (y la ingesta) corren en el single-thread executor de
 * {@link JobsRegistry} —una a la vez—, así que aquí no hay concurrencia real entre jobs; aun así la
 * caché de esqueletos es {@link ConcurrentHashMap} por defensa.
 *
 * <p>Ambas cachés dependen SOLO del dataset (aeropuertos + vuelos, estáticos), no de la demanda:
 * <ul>
 *   <li><b>grafo</b>: la malla normalizada a UTC ({@code AlgorithmMapper.mapToGraph}). Es read-only en
 *       producción (la ocupación vive en {@code GreedyRepairOperator}, no en {@code Edge}/{@code Node}),
 *       por eso puede compartirse entre corridas.</li>
 *   <li><b>skeletonCache</b>: esqueletos de ruta ({@code int[]} de edge-idx) que llena el pre-warm de
 *       cada simulación. Reutilizable mientras los edge-idx no cambien (i.e. mientras no se recargue el
 *       dataset). Tras la 1.ª corrida queda caliente ⇒ el pre-warm de las siguientes es casi instantáneo.</li>
 * </ul>
 *
 * <p>Se invalidan al recargar el dataset (ingesta), que reemplaza vuelos/aeropuertos.
 */
@Slf4j
@Component
public class MotorGrafoCache {

    private volatile Graph grafo;
    private final Map<Long, List<int[]>> skeletonCache = new ConcurrentHashMap<>();

    /** Devuelve el grafo cacheado; lo construye con {@code constructor} la primera vez (doble verificación). */
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

    /** Caché de esqueletos compartida que se inyecta a cada {@code GreedyRepairOperator}. */
    public Map<Long, List<int[]>> skeletonCache() {
        return skeletonCache;
    }

    /** Invalida ambas cachés. Llamar tras recargar el dataset: cambian los vuelos ⇒ los edge-idx también. */
    public synchronized void invalidar() {
        grafo = null;
        skeletonCache.clear();
        log.info("Caché de grafo y esqueletos invalidada (recarga de dataset).");
    }
}
