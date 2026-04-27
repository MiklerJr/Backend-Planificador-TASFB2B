package com.tasfb2b.planificador.algorithm.aco;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlgorithmACOTest {

    private Graph graph;
    private ConfigACO config;
    private CostFunction.EnvioContext envio;

    @BeforeEach
    void setUp() {
        graph = new Graph();
        config = new ConfigACO();
        config.antCount = 5;
        config.iterations = 10;
        config.alpha = 1.0;
        config.beta = 2.0;
        config.evaporation = 0.5;

        construirGrafoDemo();
    }

    @Test
    void testRutaDirectaExiste() {
        envio = new CostFunction.EnvioContext("SKBO", "SPIM", 1, 8, 0);

        AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
        aco.run("SKBO", "SPIM");

        Ant mejorAnt = aco.getMejorAnt();

        assertNotNull(mejorAnt, "Debe haber encontrado una solución");
        assertFalse(mejorAnt.path.isEmpty(), "El path no debe estar vacío");
        assertTrue(mejorAnt.path.get(0).code.equals("SKBO"),
                "El primer nodo debe ser SKBO");
        assertTrue(mejorAnt.path.get(mejorAnt.path.size() - 1).code.equals("SPIM"),
                "El último nodo debe ser SPIM");
    }

    @Test
    void testRutaConEscala() {
        envio = new CostFunction.EnvioContext("SKBO", "SVMI", 1, 8, 0);

        AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
        aco.run("SKBO", "SVMI");

        Ant mejorAnt = aco.getMejorAnt();

        assertNotNull(mejorAnt, "Debe haber encontrado una solución");
        assertTrue(mejorAnt.path.get(0).code.equals("SKBO"),
                "El origen debe ser SKBO");
    }

    @Test
    void testSinRutaPosible() {
        Node noex = new Node("NOEX");
        graph.nodes.put("NOEX", noex);

        envio = new CostFunction.EnvioContext("SKBO", "NOEX", 1, 8, 0);

        AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
        aco.run("SKBO", "NOEX");

        Ant mejorAnt = aco.getMejorAnt();

        assertTrue(mejorAnt.path.isEmpty() ||
                mejorAnt.totalCost == Double.MAX_VALUE ||
                !ultimoNodoEs(mejorAnt, "NOEX"),
                "No debe encontrar ruta válida al destino");
    }

    private boolean ultimoNodoEs(Ant ant, String codigo) {
        if (ant.path.isEmpty()) return false;
        return ant.path.get(ant.path.size() - 1).code.equals(codigo);
    }

    @Test
    void testCostosCalculados() {
        envio = new CostFunction.EnvioContext("SKBO", "SPIM", 1, 8, 0);

        AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
        aco.run("SKBO", "SPIM");

        Ant mejorAnt = aco.getMejorAnt();

        assertTrue(mejorAnt.totalCost >= 0, "El costo debe ser no negativo");
    }

    @Test
    void testVerPlanificacion() {
        envio = new CostFunction.EnvioContext("SKBO", "SPIM", 2, 8, 0);

        AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
        aco.run("SKBO", "SPIM");

        Ant mejor = aco.getMejorAnt();

        System.out.println("\n=== PLANIFICACIÓN ===");
        System.out.println("Origen: " + envio.origenICAO);
        System.out.println("Destino: " + envio.destinoICAO);
        System.out.println("Cantidad maletas: " + envio.cantidadMaletas);
        System.out.println("Hora registro: " + envio.minutosRegistro / 60 + ":" + String.format("%02d", envio.minutosRegistro % 60));
        System.out.println("Deadline (SLA): " + envio.deadlineMinutos / 60 + " horas");
        System.out.println("Costo total: " + mejor.totalCost);
        System.out.println("Ruta: " + mejor.getRutaStr());
        System.out.println("Nodos en ruta: " + mejor.path.size());
        System.out.println("========================\n");

        assertNotNull(mejor, "Debe encontrar una solución");
        assertFalse(mejor.path.isEmpty(), "La ruta no debe estar vacía");
    }

    private void construirGrafoDemo() {
        Node skbo = new Node("SKBO");
        Node seqm = new Node("SEQM");
        Node spim = new Node("SPIM");
        Node svmi = new Node("SVMI");
        Node sami = new Node("SAMI");

        skbo.storageCapacity = 600;
        seqm.storageCapacity = 500;
        spim.storageCapacity = 700;
        svmi.storageCapacity = 550;
        sami.storageCapacity = 500;

        graph.nodes.put("SKBO", skbo);
        graph.nodes.put("SEQM", seqm);
        graph.nodes.put("SPIM", spim);
        graph.nodes.put("SVMI", svmi);
        graph.nodes.put("SAMI", sami);

        agregarEdge(skbo, seqm, "08:00", "10:00", 300);
        agregarEdge(seqm, spim, "11:00", "14:00", 200);
        agregarEdge(skbo, spim, "09:00", "15:00", 150);
        agregarEdge(skbo, svmi, "07:24", "09:47", 200);
        agregarEdge(svmi, spim, "16:00", "18:00", 150);
        agregarEdge(skbo, sami, "06:00", "08:00", 200);
        agregarEdge(sami, spim, "09:00", "11:00", 150);
    }

    private void agregarEdge(Node from, Node to, String dep, String arr, int cap) {
        Edge e = new Edge();
        e.from = from;
        e.to = to;
        e.departureTime = dep;
        e.arrivalTime = arr;
        e.capacity = cap;
        e.cost = CostFunction.calcularDuracionMinutos(dep, arr);
        graph.edges.add(e);
    }
}