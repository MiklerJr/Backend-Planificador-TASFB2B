package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.List;

class DiagnosticoSecuencialTest {

    private final EnvioLoader envioLoader = new EnvioLoader();
    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void procesar10EnviosSKBO() {
        System.out.println("=== PROCESAR 10 ENVIOS SKBO SECUENCIALMENTE ===\n");

        List<Aeropuerto> airports = aeropuertoLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        List<EnvioDTO> envios = envioLoader.cargarEnvios("SKBO").subList(0, 10);

        ConfigACO config = new ConfigACO();
        config.antCount = 10;
        config.iterations = 50;

        int exitos = 0;
        int fallidos = 0;

        for (int i = 0; i < envios.size(); i++) {
            EnvioDTO e = envios.get(i);

            Node origenNode = graph.nodes.get("SKBO");
            int storageAntes = origenNode.storageUsed;

            CostFunction.EnvioContext context = new CostFunction.EnvioContext(
                    "SKBO", e.destinoICAO, e.cantidadMaletas, e.horaRegistro, e.minutoRegistro);

            AlgorithmACO aco = new AlgorithmACO(graph, config, context);
            aco.run("SKBO", e.destinoICAO);

            Ant mejor = aco.getMejorAnt();
            if (mejor != null && !mejor.path.isEmpty()) {
                System.out.println((i+1) + ". " + e.id + " -> " + e.destinoICAO +
                        " | ÉXITO: " + mejor.getRutaStr() + " (" + mejor.edgesPath.size() + " flights)");
                exitos++;

                for (Edge edge : mejor.edgesPath) {
                    edge.useCapacity(e.cantidadMaletas);
                }
            } else {
                System.out.println((i+1) + ". " + e.id + " -> " + e.destinoICAO + " | FALLÓ");
                fallidos++;
            }

            int storageDesp = origenNode.storageUsed;
            System.out.println("    Storage: " + storageAntes + " -> " + storageDesp + "/" + origenNode.storageCapacity);
        }

        System.out.println("\n=== RESUMEN ===");
        System.out.println("Éxitos: " + exitos + "/" + envios.size());
        System.out.println("Fallidos: " + fallidos);
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