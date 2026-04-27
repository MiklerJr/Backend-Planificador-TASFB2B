package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebugFallasTest {

    private final EnvioLoader envioLoader = new EnvioLoader();
    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void debugProcesarSKBO() {
        System.out.println("=== DEBUG: Procesar envíos de SKBO ===");

        List<Aeropuerto> airports = cargarAeropuertos();
        List<String> vuelos = cargarVuelosReales();
        Graph graph = graphBuilder.build(airports, vuelos);

        ConfigACO config = new ConfigACO();
        config.antCount = 5;
        config.iterations = 30;

        List<EnvioDTO> envios = envioLoader.cargarEnvios("SKBO");

        for (int i = 0; i < Math.min(5, envios.size()); i++) {
            EnvioDTO e = envios.get(i);

            System.out.println("\n--- Envío " + e.id + " ---");
            System.out.println("  Origen: " + "SKBO" + ", Destino: " + e.destinoICAO);
            System.out.println("  Maletas: " + e.cantidadMaletas);
            System.out.println("  Hora registro: " + String.format("%02d:%02d", e.horaRegistro, e.minutoRegistro));

            CostFunction.EnvioContext context = new CostFunction.EnvioContext(
                    "SKBO", e.destinoICAO, e.cantidadMaletas,
                    e.horaRegistro, e.minutoRegistro
            );

            System.out.println("  Deadline: " + context.deadlineMinutos + " min");

            AlgorithmACO aco = new AlgorithmACO(graph, config, context);
            aco.run("SKBO", e.destinoICAO);

            Ant mejor = aco.getMejorAnt();

            if (mejor != null && !mejor.path.isEmpty()) {
                System.out.println("  ÉXITO!");
                System.out.println("  Ruta: " + mejor.getRutaStr());
                System.out.println("  Costo: " + mejor.totalCost);

                for (Edge edge : graph.edges) {
                    if (edge.from.code.equals("SKBO") && edge.to.code.equals(e.destinoICAO)) {
                        System.out.println("    Flight " + edge.departureTime + "->" + edge.arrivalTime +
                                ": cap=" + edge.capacity + ", used=" + edge.usedCapacity);
                    }
                }
            } else {
                System.out.println("  FALLÓ");

                for (Edge edge : graph.edges) {
                    if (edge.from.code.equals("SKBO") && edge.to.code.equals(e.destinoICAO)) {
                        System.out.println("    Flight " + edge.departureTime + "->" + edge.arrivalTime +
                                ": cap=" + edge.capacity + ", used=" + edge.usedCapacity +
                                ", puede=" + edge.hasCapacity(e.cantidadMaletas));
                    }
                }
            }
        }
    }

    private List<Aeropuerto> cargarAeropuertos() {
        return new AeropuertoLoader().cargarAeropuertos();
    }

    private List<String> cargarVuelosReales() {
        List<String> vuelos = new java.util.ArrayList<>();
        try {
            java.io.InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("data/planes_vuelo.txt");
            if (is == null) {
                return vuelos;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is));
            String linea;
            while ((linea = br.readLine()) != null) {
                if (!linea.trim().isEmpty()) {
                    vuelos.add(linea.trim());
                }
            }
            br.close();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
        return vuelos;
    }
}