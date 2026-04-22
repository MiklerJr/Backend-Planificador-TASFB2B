package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DiagnosticoDebugTest {

    private final AeropuertoLoader airportLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void debugSKBO() {
        System.out.println("=== DEBUG SKBO -> SPIM ===\n");

        List<Aeropuerto> airports = airportLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        Node skbo = graph.nodes.get("SKBO");
        Node spim = graph.nodes.get("SPIM");

        System.out.println("SKBO in graph: " + (skbo != null));
        System.out.println("SPIM in graph: " + (spim != null));
        System.out.println("SKBO storage: " + skbo.storageUsed + "/" + skbo.storageCapacity);

        System.out.println("\nTrying to find path manually:");
        List<Edge> desdeSKBO = graph.getEdgesFrom("SKBO");
        System.out.println("Edges from SKBO: " + desdeSKBO.size());

        int found = 0;
        for (Edge e1 : desdeSKBO) {
            if (e1.hasCapacity(2) && e1.to.hasStorageCapacity(2)) {
                for (Edge e2 : graph.getEdgesFrom(e1.to.code)) {
                    if (e2.to.code.equals("SPIM") && e2.hasCapacity(2)) {
                        boolean tieneTiempo = CostFunction.tieneTiempoMinimoEscala(e1, e2);
                        if (tieneTiempo) {
                            System.out.println("  Path: " + e1.from.code + " -> " + e1.to.code +
                                    " -> " + e2.to.code);
                            found++;
                            if (found >= 3) return;
                        }
                    }
                }
            }
        }
        System.out.println("Paths found: " + found);
    }

    private List<String> loadFlights() {
        List<String> flights = new ArrayList<>();
        try {
            java.io.InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("data/planes_vuelo.txt");
            if (is == null) return flights;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    flights.add(line.trim());
                }
            }
            br.close();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
        return flights;
    }
}