package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DiagnosticoFallasTest {

    private final EnvioLoader envioLoader = new EnvioLoader();
    private final AeropuertoLoader airportLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void diagnosticoFallasSEQM() {
        System.out.println("=== DIAGNOSTICO FALLAS SEQM ===\n");

        List<Aeropuerto> airports = airportLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        List<EnvioDTO> envios = envioLoader.cargarEnvios("SEQM").subList(0, 20);

        System.out.println("Destinos únicos:");
        List<String> destinos = envios.stream().map(e -> e.destinoICAO).distinct().toList();
        System.out.println(destinos);

        System.out.println("\nVerificando cada destino:");
        for (String destino : destinos) {
            Node nodoDestino = graph.nodes.get(destino);
            boolean existeEnGrafo = nodoDestino != null;

            List<Edge> directos = graph.edges.stream()
                    .filter(e -> e.from.code.equals("SEQM") && e.to.code.equals(destino))
                    .toList();

            int rutasConEscalas = 0;
            for (Edge e1 : graph.edges.stream().filter(e -> e.from.code.equals("SEQM")).toList()) {
                for (Edge e2 : graph.edges.stream()
                        .filter(e -> e.from.code.equals(e1.to.code) && e.to.code.equals(destino))
                        .toList()) {
                    rutasConEscalas++;
                    break;
                }
            }

            System.out.println(destino + ": existe=" + existeEnGrafo +
                    " | directos=" + directos.size() +
                    " | con escalas=" + rutasConEscalas);
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