package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

class DiagnosticoRutasTest {

    private final EnvioLoader envioLoader = new EnvioLoader();
    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void verTodosDestinosYsusFlights() {
        System.out.println("=== TODOS LOS DESTINOS Y SUS FLIGHTS ===\n");

        List<Aeropuerto> airports = aeropuertoLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        List<String> destinos = envioLoader.cargarEnvios("SKBO").stream()
                .map(e -> e.destinoICAO)
                .distinct()
                .toList();

        System.out.println("Destinos únicos en envíos: " + destinos.size() + "\n");

        for (String destino : destinos) {
            List<Edge> directos = graph.edges.stream()
                    .filter(e -> e.from.code.equals("SKBO") && e.to.code.equals(destino))
                    .toList();

            long conexiones = graph.edges.stream()
                    .filter(e -> e.from.code.equals("SKBO"))
                    .filter(e -> {
                        List<Edge> fromNext = graph.edges.stream()
                                .filter(ed -> ed.from.code.equals(e.to.code) && ed.to.code.equals(destino))
                                .toList();
                        return !fromNext.isEmpty();
                    })
                    .count();

            System.out.println(destino + " | directos=" + directos.size() + " | con escalas=" + conexiones);
        }

        System.out.println("\n=== VERIFICAR: Por destino ===\n");

        for (String destino : destinos) {
            Node nodoDestino = graph.nodes.get(destino);
            if (nodoDestino == null) {
                System.out.println(destino + " -> NO EXISTE EN EL GRAFO");
                continue;
            }

            long incoming = graph.edges.stream()
                    .filter(e -> e.to.code.equals(destino))
                    .count();

            System.out.println(destino + ": incoming edges=" + incoming);
        }
    }

    @Test
    void verTodasRutasConEscalas() {
        System.out.println("=== RUTAS CON ESCALAS DESDE SKBO ===\n");

        List<Aeropuerto> airports = aeropuertoLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        List<String> destinos = envioLoader.cargarEnvios("SKBO").stream()
                .map(e -> e.destinoICAO)
                .distinct()
                .toList();

        for (String destino : destinos) {
            List<Edge> stage1 = graph.edges.stream()
                    .filter(e -> e.from.code.equals("SKBO"))
                    .toList();

            long rutasConEscalas = 0;
            for (Edge e1 : stage1) {
                long stage2 = graph.edges.stream()
                        .filter(e2 -> e2.from.code.equals(e1.to.code) && e2.to.code.equals(destino))
                        .count();
                if (stage2 > 0) rutasConEscalas++;
            }

            if (rutasConEscalas == 0) {
                System.out.println(destino + ": 0 rutas con escalas (solo directo)");
            }
        }
    }

    @Test
    void verAlmacenesYsuCapacidad() {
        System.out.println("=== CAPACIDAD DE ALMACENES ===\n");

        List<Aeropuerto> airports = aeropuertoLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        List<String> destinos = envioLoader.cargarEnvios("SKBO").stream()
                .map(e -> e.destinoICAO)
                .distinct()
                .toList();

        for (String destino : destinos) {
            Node n = graph.nodes.get(destino);
            if (n != null) {
                System.out.println(destino + ": storageCap=" + n.storageCapacity + " | used=" + n.storageUsed);
            }
        }
    }

    private List<String> loadFlights() {
        List<String> flights = new java.util.ArrayList<>();
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