package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DiagnosticoRutasSKBOTest {

    private final EnvioLoader envioLoader = new EnvioLoader();
    private final AeropuertoLoader airportLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void verFlightsYGrafoSKBO() {
        System.out.println("=== SKBO FLIGHTS Y CAPACIDAD ===\n");

        List<Aeropuerto> airports = airportLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        List<Edge> desdeSKBO = graph.edges.stream()
                .filter(e -> e.from.code.equals("SKBO"))
                .toList();

        System.out.println("Flights desde SKBO: " + desdeSKBO.size());

        System.out.println("\nPrimeros 5 flights desde SKBO:");
        desdeSKBO.stream().limit(5).forEach(e ->
                System.out.println("  " + e.from.code + " -> " + e.to.code +
                        " (cap=" + e.capacity + ", used=" + e.usedCapacity + ")"));

        System.out.println("\nVerificando rutas SPIM:");
        for (Edge e1 : desdeSKBO) {
            Node nodoEscala = graph.nodes.get(e1.to.code);
            System.out.println("  " + e1.to.code + " storage=" + nodoEscala.storageUsed + "/" + nodoEscala.storageCapacity);

            List<Edge> etapa2 = graph.edges.stream()
                    .filter(e2 -> e2.from.code.equals(e1.to.code) && e2.to.code.equals("SPIM"))
                    .toList();
            if (!etapa2.isEmpty()) {
                System.out.println("    -> SPIM: " + etapa2.size() + " options");
                for (Edge e2 : etapa2) {
                    System.out.println("      " + e2.from.code + " -> " + e2.to.code +
                            " cap=" + e2.capacity + " used=" + e2.usedCapacity);
                }
                break;
            }
        }
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