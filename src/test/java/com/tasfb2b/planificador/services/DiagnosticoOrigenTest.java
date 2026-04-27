package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.List;

class DiagnosticoOrigenTest {

    private final EnvioLoader envioLoader = new EnvioLoader();
    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void probarSKBO() {
        System.out.println("=== SKBO ===");
        probarOrigen("SKBO");
    }

    @Test
    void probarSEQM() {
        System.out.println("=== SEQM ===");
        probarOrigen("SEQM");
    }

    @Test
    void probarEDDI() {
        System.out.println("=== EDDI ===");
        probarOrigen("EDDI");
    }

    @Test
    void probarLOWW() {
        System.out.println("=== LOWW ===");
        probarOrigen("LOWW");
    }

    private void probarOrigen(String origen) {
        List<Aeropuerto> airports = aeropuertoLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        Node nodoOrigen = graph.nodes.get(origen);
        System.out.println(origen + " storage: used=" + nodoOrigen.storageUsed + " cap=" + nodoOrigen.storageCapacity);

        List<Edge> outbound = graph.edges.stream()
                .filter(e -> e.from.code.equals(origen))
                .toList();
        System.out.println("Outbound flights: " + outbound.size());

        long withCapacity = outbound.stream()
                .filter(e -> e.hasCapacity(2))
                .count();
        System.out.println("With capacity (2 bags): " + withCapacity);

        List<EnvioDTO> envios = envioLoader.cargarEnvios(origen).subList(0, 3);
        System.out.println("\nPrimeros 3 envíos:");

        ConfigACO config = new ConfigACO();
        config.antCount = 3;
        config.iterations = 20;

        for (EnvioDTO e : envios) {
            System.out.println("  " + e.id + " -> " + e.destinoICAO + " (" + e.cantidadMaletas + " maletas)");

            CostFunction.EnvioContext context = new CostFunction.EnvioContext(
                    origen, e.destinoICAO, e.cantidadMaletas, e.horaRegistro, e.minutoRegistro);

            AlgorithmACO aco = new AlgorithmACO(graph, config, context);
            aco.run(origen, e.destinoICAO);

            Ant mejor = aco.getMejorAnt();
            if (mejor != null && !mejor.path.isEmpty()) {
                System.out.println("    ÉXITO: " + mejor.getRutaStr() + " | escalas=" + (mejor.path.size() - 2));
            } else {
                System.out.println("    FALLÓ");
            }
        }

        Node nodoFinal = graph.nodes.get(origen);
        System.out.println("\nAl final: storage=" + nodoFinal.storageUsed + "/" + nodoFinal.storageCapacity);
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