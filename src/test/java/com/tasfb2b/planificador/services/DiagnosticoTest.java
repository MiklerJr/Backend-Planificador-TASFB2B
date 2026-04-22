package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticoTest {

    private final EnvioLoader envioLoader = new EnvioLoader();
    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();

    @Test
    void diagnosticoEnvios() {
        System.out.println("=== DIAGNÓSTICO DE ENVÍOS ===");

        List<EnvioDTO> envios = envioLoader.cargarEnvios("SKBO");

        System.out.println("Total envíos cargados: " + envios.size());

        if (envios.isEmpty()) {
            System.out.println("ERROR: No se cargaron envíos!");
            fail("No se cargaron envíos");
        }

        System.out.println("\nPrimeros 5 envíos:");
        for (int i = 0; i < Math.min(5, envios.size()); i++) {
            EnvioDTO e = envios.get(i);
            System.out.println("  " + e.id + " -> " + e.destinoICAO +
                    " (" + e.cantidadMaletas + " maletas) a las " +
                    String.format("%02d:%02d", e.horaRegistro, e.minutoRegistro));
        }

        assertFalse(envios.isEmpty(), "Debe cargar al menos un envío");
        assertTrue(envios.get(0).cantidadMaletas > 0, "La cantidad de maletas debe ser > 0");
    }

    @Test
    void diagnosticoGrafo() {
        System.out.println("\n=== DIAGNÓSTICO DEL GRAFO (datos reales) ===");

        List<Aeropuerto> aeropuertos = aeropuertoLoader.cargarAeropuertos();
        System.out.println("Aeropuertos cargados: " + aeropuertos.size());

        List<String> vuelos = cargarVuelosReales();
        System.out.println("Flights cargados: " + vuelos.size());

        Graph graph = graphBuilder.build(aeropuertos, vuelos);

        System.out.println("Nodos en el grafo: " + graph.nodes.size());
        System.out.println("Edges en el grafo: " + graph.edges.size());

        System.out.println("\nVerificando nodos de los primeros envíos...");
        List<EnvioDTO> envios = envioLoader.cargarEnvios("SKBO");

        for (int i = 0; i < Math.min(3, envios.size()); i++) {
            EnvioDTO e = envios.get(i);
            boolean existeOrigen = graph.nodes.containsKey("SKBO");
            boolean existeDestino = graph.nodes.containsKey(e.destinoICAO);

            System.out.println("  Envío " + e.id + ": SKBO existe=" + existeOrigen +
                    ", " + e.destinoICAO + " existe=" + existeDestino);
        }

        assertFalse(graph.nodes.isEmpty(), "El grafo debe tener nodos");
        assertFalse(graph.edges.isEmpty(), "El grafo debe tener aristas");
    }

    @Test
    void diagnosticoEnvioUnico() {
        System.out.println("\n=== DIAGNÓSTICO DE ENVÍO ÚNICO ===");

        List<EnvioDTO> envios = envioLoader.cargarEnvios("SKBO");
        EnvioDTO e = envios.get(0);

        System.out.println("Primer envío:");
        System.out.println("  ID: " + e.id);
        System.out.println("  Destino: " + e.destinoICAO);
        System.out.println("  Maletas: " + e.cantidadMaletas);
        System.out.println("  Hora: " + e.horaRegistro + ":" + String.format("%02d", e.minutoRegistro));

        assertTrue(e.cantidadMaletas > 0, "Las maletas deben ser > 0");
    }

    @Test
    void diagnosticoACOConEnvioReal() {
        System.out.println("\n=== DIAGNÓSTICO ACO CON ENVÍO REAL ===");

        List<Aeropuerto> aeropuertos = aeropuertoLoader.cargarAeropuertos();
        List<String> vuelos = cargarVuelosReales();
        Graph graph = graphBuilder.build(aeropuertos, vuelos);

        System.out.println("Grafo: " + graph.nodes.size() + " nodos, " + graph.edges.size() + " edges");

        List<EnvioDTO> envios = envioLoader.cargarEnvios("SKBO");
        EnvioDTO e = envios.get(0);

        System.out.println("\nProcesando envío: " + e.id);
        System.out.println("  Origen: SKBO, Destino: " + e.destinoICAO);
        System.out.println("  Maletas: " + e.cantidadMaletas);

        Node skbo = graph.nodes.get("SKBO");
        Node spim = graph.nodes.get(e.destinoICAO);

        System.out.println("\n  SKBO: storageCapacity=" + skbo.storageCapacity);
        System.out.println("  " + e.destinoICAO + ": storageCapacity=" + spim.storageCapacity);

        System.out.println("\n  Flights SKBO -> " + e.destinoICAO + ":");
        for (Edge edge : graph.edges) {
            if (edge.from.code.equals("SKBO") && edge.to.code.equals(e.destinoICAO)) {
                System.out.println("    " + edge.departureTime + " -> " + edge.arrivalTime +
                        ": capacidad=" + edge.capacity +
                        ", used=" + edge.usedCapacity +
                        ", tieneEspacio=" + edge.hasCapacity(e.cantidadMaletas));
            }
        }

        CostFunction.EnvioContext envio = new CostFunction.EnvioContext(
                "SKBO", e.destinoICAO, e.cantidadMaletas,
                e.horaRegistro, e.minutoRegistro
        );

        ConfigACO config = new ConfigACO();
        config.antCount = 10;
        config.iterations = 50;

        AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
        aco.run("SKBO", e.destinoICAO);

        Ant mejor = aco.getMejorAnt();

        if (mejor != null && !mejor.path.isEmpty()) {
            System.out.println("\n  ÉXITO!");
            System.out.println("  Ruta: " + mejor.getRutaStr());
            System.out.println("  Costo: " + mejor.totalCost);

            for (Node n : mejor.path) {
                System.out.println("    Nodo " + n.code + ": storageUsed=" + n.storageUsed +
                        ", storageCapacity=" + n.storageCapacity +
                        ", ocupación=" + String.format("%.1f%%", n.getOcupacionAlmacen() * 100));
            }
        } else {
            System.out.println("\n  FALLÓ: No se encontró ruta");

            System.out.println("  Verificando flights desde SKBO:");
            for (Edge edge : graph.edges) {
                if (edge.from.code.equals("SKBO")) {
                    boolean tieneCapacidad = edge.hasCapacity(e.cantidadMaletas);
                    System.out.println("    SKBO -> " + edge.to.code +
                            ": capacidad=" + edge.capacity +
                            ", used=" + edge.usedCapacity +
                            ", tieneEspacio=" + tieneCapacidad);
                }
            }
        }
    }

    private List<String> cargarVuelosReales() {
        List<String> vuelos = new ArrayList<>();
        try {
            java.io.InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("data/planes_vuelo.txt");
            if (is == null) {
                System.out.println("Archivo de vuelos no encontrado");
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
            System.out.println("Error cargando vuelos: " + e.getMessage());
        }
        return vuelos;
    }
}