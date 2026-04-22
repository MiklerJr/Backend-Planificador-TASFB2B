package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticoCapacidadTest {

    private final EnvioLoader envioLoader = new EnvioLoader();
    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void diagnosticoCapacidadVuelos() {
        System.out.println("=== DIAGNÓSTICO: Capacidad de Vuelos ===\n");

        List<Aeropuerto> airports = aeropuertoLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        System.out.println("--- Estado INICIAL del grafo ---");
        System.out.println("Total edges: " + graph.edges.size());

        long flightsFromSKBO = graph.edges.stream()
                .filter(e -> e.from.code.equals("SKBO"))
                .count();
        System.out.println("Flights desde SKBO: " + flightsFromSKBO);

        System.out.println("\n--- Capacidad de flights SKBO -> SPIM (inicial) ---");
        graph.edges.stream()
                .filter(e -> e.from.code.equals("SKBO") && e.to.code.equals("SPIM"))
                .forEach(e -> System.out.println("  " + e.departureTime + "->" + e.arrivalTime +
                        " | cap=" + e.capacity + " | used=" + e.usedCapacity + " | free=" + (e.capacity - e.usedCapacity)));

        System.out.println("\n--- Procesando PRIMEROS 5 envíos de SKBO ---");
        List<EnvioDTO> envios = envioLoader.cargarEnvios("SKBO");

        ConfigACO config = new ConfigACO();
        config.antCount = 5;
        config.iterations = 30;

        for (int i = 0; i < Math.min(5, envios.size()); i++) {
            EnvioDTO e = envios.get(i);
            System.out.println("\nProcesando envio " + e.id + " -> " + e.destinoICAO + " (" + e.cantidadMaletas + " maletas)");

            CostFunction.EnvioContext context = new CostFunction.EnvioContext(
                    "SKBO", e.destinoICAO, e.cantidadMaletas, e.horaRegistro, e.minutoRegistro);

            AlgorithmACO aco = new AlgorithmACO(graph, config, context);
            aco.run("SKBO", e.destinoICAO);

            Ant mejor = aco.getMejorAnt();
            if (mejor != null && !mejor.path.isEmpty()) {
                System.out.println("  ÉXITO: " + mejor.getRutaStr() + " | costo=" + mejor.totalCost);
            } else {
                System.out.println("  FALLÓ: No se encontró ruta");

                System.out.println("  Razón:");
                boolean tieneVuelo = graph.edges.stream()
                        .anyMatch(edge -> edge.from.code.equals("SKBO") && edge.to.code.equals(e.destinoICAO));
                System.out.println("    - Existe vuelo directo: " + tieneVuelo);

                if (tieneVuelo) {
                    System.out.println("    - Vuelos disponibles:");
                    graph.edges.stream()
                            .filter(edge -> edge.from.code.equals("SKBO") && edge.to.code.equals(e.destinoICAO))
                            .forEach(edge -> System.out.println("      " + edge.departureTime + "->" + edge.arrivalTime +
                                    " | cap=" + edge.capacity + " | used=" + edge.usedCapacity + " | free=" + (edge.capacity - edge.usedCapacity)));
                }
            }
        }

        System.out.println("\n--- Capacidad de flights SKBO -> SPIM (después de 5 envíos) ---");
        graph.edges.stream()
                .filter(e -> e.from.code.equals("SKBO") && e.to.code.equals("SPIM"))
                .forEach(e -> System.out.println("  " + e.departureTime + "->" + e.arrivalTime +
                        " | cap=" + e.capacity + " | used=" + e.usedCapacity + " | free=" + (e.capacity - e.usedCapacity)));

        System.out.println("\n--- Estado ALMACENES después de 5 envíos ---");
        Node skbo = graph.nodes.get("SKBO");
        System.out.println("SKBO: storageUsed=" + skbo.storageUsed + " | capacity=" + skbo.storageCapacity);
    }

    @Test
    void verTodosFlightsSKBO() {
        System.out.println("=== TODOS LOS FLIGHTS DESDE SKBO ===\n");

        List<Aeropuerto> airports = aeropuertoLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        System.out.println("Flights desde SKBO por destino:");
        graph.edges.stream()
                .filter(e -> e.from.code.equals("SKBO"))
                .forEach(e -> System.out.println("  SKBO -> " + e.to.code +
                        " (" + e.departureTime + "->" + e.arrivalTime + ")" +
                        " cap=" + e.capacity + " used=" + e.usedCapacity));
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