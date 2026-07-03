package com.tasfb2b.planificador.algoritmo.grafo;

import jakarta.validation.constraints.NotBlank;
import java.util.*;

public class Grafo {

    public Map<String, Nodo> nodos = new HashMap<>();
    public List<Arista> aristas = new ArrayList<>();

    private final Map<String, List<Arista>> listaAdyacencia = new HashMap<>();

    public void agregarNodo(@NotBlank(message = "El aeropuerto debe tener un codigo de identificación") String codigo) {
        if (!nodos.containsKey(codigo)) {
            nodos.put(codigo, new Nodo(codigo));
        }
    }

    public void agregarArista(Arista edge) {
        aristas.add(edge);
        if (edge.origen != null) {
            listaAdyacencia.computeIfAbsent(edge.origen.codigo, k -> new ArrayList<>()).add(edge);
        }
    }

    public List<Arista> getVecinos(String nodeId) {
        List<Arista> result = listaAdyacencia.get(nodeId);
        return result != null ? result : Collections.emptyList();
    }

    public List<Arista> getAristasDesde(String nodeCode) {
        return getVecinos(nodeCode);
    }
}
