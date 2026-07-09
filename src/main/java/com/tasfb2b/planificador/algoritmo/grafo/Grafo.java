package com.tasfb2b.planificador.algoritmo.grafo;

import jakarta.validation.constraints.NotBlank;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Grafo {

    // Estructuras concurrentes: las altas EN CALIENTE (AltasEnCalienteService) mutan el grafo desde el
    // hilo worker mientras los endpoints HTTP (GET /aeropuertos, PUT capacidad, consultas de jobs) lo
    // leen. Escrituras rarísimas (una por alta/reversión) ⇒ copy-on-write es la opción correcta.
    public Map<String, Nodo> nodos = new ConcurrentHashMap<>();
    public List<Arista> aristas = new CopyOnWriteArrayList<>();

    private final Map<String, List<Arista>> listaAdyacencia = new ConcurrentHashMap<>();

    public void agregarNodo(@NotBlank(message = "El aeropuerto debe tener un codigo de identificación") String codigo) {
        if (!nodos.containsKey(codigo)) {
            nodos.put(codigo, new Nodo(codigo));
        }
    }

    /** Alta EN CALIENTE de un aeropuerto: crea (o devuelve) el nodo con su capacidad de almacén. */
    public Nodo agregarNodo(String codigo, int capacidadAlmacen) {
        Nodo nodo = nodos.computeIfAbsent(codigo, Nodo::new);
        nodo.capacidad = capacidadAlmacen;
        nodo.capacidadAlmacen = capacidadAlmacen;
        return nodo;
    }

    /** Reversión de altas EN CALIENTE: quita el nodo y su entrada de adyacencia (sus aristas
     *  efímeras se recortan aparte con {@link #recortarAristasDesde}). */
    public void eliminarNodo(String codigo) {
        if (codigo == null) return;
        nodos.remove(codigo);
        listaAdyacencia.remove(codigo);
    }

    public void agregarArista(Arista edge) {
        aristas.add(edge);
        if (edge.origen != null) {
            listaAdyacencia.computeIfAbsent(edge.origen.codigo, k -> new CopyOnWriteArrayList<>()).add(edge);
        }
    }

    public List<Arista> getVecinos(String nodeId) {
        List<Arista> result = listaAdyacencia.get(nodeId);
        return result != null ? result : Collections.emptyList();
    }

    public List<Arista> getAristasDesde(String nodeCode) {
        return getVecinos(nodeCode);
    }

    /**
     * Reversión de altas EN CALIENTE: quita de {@code aristas} (y de la adyacencia) toda arista con
     * {@code indice >= indiceBase}. Las altas son append-only (índices al final), así que recortar el
     * tail restaura exactamente el baseline y ningún índice existente se mueve.
     */
    public void recortarAristasDesde(int indiceBase) {
        for (int i = aristas.size() - 1; i >= 0 && aristas.get(i).indice >= indiceBase; i--) {
            Arista e = aristas.remove(i);
            if (e.origen != null) {
                List<Arista> ady = listaAdyacencia.get(e.origen.codigo);
                if (ady != null) ady.remove(e);
            }
        }
    }
}
