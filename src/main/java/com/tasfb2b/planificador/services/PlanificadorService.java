package com.tasfb2b.planificador.services;


import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlanificadorService {

    private final AeropuertoLoader aeropuertoLoader;
    private final GraphBuilder graphBuilder;

    public PlanificadorService(AeropuertoLoader aeropuertoLoader,
                               GraphBuilder graphBuilder) {
        this.aeropuertoLoader = aeropuertoLoader;
        this.graphBuilder = graphBuilder;
    }

    public String ejecutarACO(String origen, String destino) {

        // 1. CARGAR DATOS
        List<Aeropuerto> aeropuertos = aeropuertoLoader.cargarAeropuertos();

        List<String> vuelos = cargarVuelosDemo(); // luego lo conectas a BD o archivo

        // 2. CONSTRUIR GRAFO
        Graph graph = graphBuilder.build(aeropuertos, vuelos);

        // 3. CONFIGURAR ACO
        ConfigACO config = new ConfigACO();
        config.antCount = 20;
        config.iterations = 100;

        AlgorithmACO aco = new AlgorithmACO(graph, config);

        // 4. EJECUTAR ALGORITMO
        aco.run(origen, destino);

        return "ACO ejecutado de " + origen + " a " + destino;
    }

    // =========================
    // TEMPORAL (luego será BD o loader)
    // =========================
    private List<String> cargarVuelosDemo() {

        return List.of(
                "SKBO-SEQM-08:00-10:00-120",
                "SEQM-SPIM-11:00-14:00-100",
                "SKBO-SPIM-09:00-15:00-80",
                "SPIM-SVMI-16:00-18:00-60"
        );
    }
}