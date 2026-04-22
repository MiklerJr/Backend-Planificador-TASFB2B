package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DiagnosticoDetalladoTest {

    private final EnvioLoader envioLoader = new EnvioLoader();
    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void probarSKBOconDetalle() {
        System.out.println("=== SKBO - DETALLE ===\n");

        List<Aeropuerto> airports = aeropuertoLoader.cargarAeropuertos();
        List<String> flights = loadFlights();
        Graph graph = graphBuilder.build(airports, flights);

        List<EnvioDTO> envios = envioLoader.cargarEnvios("SKBO").subList(0, 10);

        ConfigACO config = new ConfigACO();
        config.antCount = 50;
        config.iterations = 200;
        config.alpha = 1.0;
        config.beta = 3.0;

        int exitos = 0;
        for (EnvioDTO e : envios) {
            CostFunction.EnvioContext context = new CostFunction.EnvioContext(
                    "SKBO", e.destinoICAO, e.cantidadMaletas, e.horaRegistro, e.minutoRegistro);

            AlgorithmACO aco = new AlgorithmACO(graph, config, context);
            aco.run("SKBO", e.destinoICAO);

            Ant mejor = aco.getMejorAnt();
            if (mejor != null && !mejor.path.isEmpty()) {
                System.out.println(e.id + " -> " + e.destinoICAO + ": ");
                System.out.println("  ÉXITO: " + mejor.getRutaStr() + " (" + mejor.edgesPath.size() + " flights)");
                for (Edge edge : mejor.edgesPath) {
                    edge.useCapacity(e.cantidadMaletas);
                }
                exitos++;
            } else {
                System.out.println(e.id + " -> " + e.destinoICAO + ": FALLÓ");
            }
        }
        System.out.println("\nÉxitos: " + exitos + "/10");
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