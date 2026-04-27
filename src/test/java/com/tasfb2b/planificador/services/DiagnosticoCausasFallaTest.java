package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DiagnosticoCausasFallaTest {

    private final AeropuertoLoader airportLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void verificarRutasDesdeSKBO() {
        System.out.println("=== VERIFICAR RUTAS DESDE SKBO ===\n");

        List<Aeropuerto> airports = airportLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        String[] destinos = {"SPIM", "OERK", "SEQM", "SVMI", "SBBR", "EDDI", "LATI", "OSDI",
                "OAKB", "SCEL", "VIDP", "UMMS", "OPKC", "LKPR", "LOWW", "UBBB",
                "OMDB", "OYSN", "OERK", "EHAM", "EKCH", "OJAI", "SGAS", "LDZA"};

        for (String destino : destinos) {
            boolean tieneDirecto = graph.edges.stream()
                    .anyMatch(e -> e.from.code.equals("SKBO") && e.to.code.equals(destino));

            boolean tieneEscalas = tieneDirecto && tieneRutaConEscalas(graph, "SKBO", destino);

            System.out.println(destino + " | directo=" + tieneDirecto + " | escalas=" + tieneEscalas);
        }
    }

    private boolean tieneRutaConEscalas(Graph graph, String origen, String destino) {
        for (var e1 : graph.edges) {
            if (e1.from.code.equals(origen)) {
                for (var e2 : graph.edges) {
                    if (e2.from.code.equals(e1.to.code) && e2.to.code.equals(destino)) {
                        return true;
                    }
                }
            }
        }
        return false;
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