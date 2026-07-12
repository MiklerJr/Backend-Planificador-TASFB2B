package com.tasfb2b.planificador.algoritmo.grafo;

import jakarta.validation.constraints.NotBlank;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Grafo {

    public Map<String, Nodo> nodos = new ConcurrentHashMap<>();
    public List<Arista> aristas = new CopyOnWriteArrayList<>();

    private final Map<String, List<Arista>> listaAdyacencia = new ConcurrentHashMap<>();

    public void agregarNodo(@NotBlank(message = "El aeropuerto debe tener un codigo de identificación") String codigo) {
        if (!nodos.containsKey(codigo)) {
            nodos.put(codigo, new Nodo(codigo));
        }
    }

    public Nodo agregarNodo(String codigo, int capacidadAlmacen) {
        Nodo nodo = nodos.computeIfAbsent(codigo, Nodo::new);
        nodo.capacidad = capacidadAlmacen;
        nodo.capacidadAlmacen = capacidadAlmacen;
        return nodo;
    }

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
