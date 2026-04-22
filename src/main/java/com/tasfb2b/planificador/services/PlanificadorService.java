package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.*;
import com.tasfb2b.planificador.dto.EnvioDTO;
import com.tasfb2b.planificador.dto.PlanificacionResultado;
import com.tasfb2b.planificador.dto.ResumenPlanificacionGlobal;
import com.tasfb2b.planificador.model.Aeropuerto;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlanificadorService {

    private final AeropuertoLoader aeropuertoLoader;
    private final GraphBuilder graphBuilder;
    private final EnvioLoader envioLoader;

    private static final String[] ORIGENES_DISPONIBLES = {
            "SKBO", "SEQM", "SVMI", "SBBR", "SPIM", "SLLP", "SCEL", "SABE",
            "SGAS", "SUAA", "LATI", "EDDI", "LOWW", "EBCI", "UMMS", "LBSF",
            "LKPR", "LDZA", "EKCH", "EHAM", "VIDP", "OSDI", "OERK", "OMDB",
            "OAKB", "OOMS", "OYSN", "OPKC", "OJAI", "UBBB"
    };

    public PlanificadorService(AeropuertoLoader aeropuertoLoader,
                               GraphBuilder graphBuilder,
                               EnvioLoader envioLoader) {
        this.aeropuertoLoader = aeropuertoLoader;
        this.graphBuilder = graphBuilder;
        this.envioLoader = envioLoader;
    }

    public ResumenPlanificacionGlobal procesarTodosLosOrigenes() {
        return procesarTodosLosOrigenesConLimite(Integer.MAX_VALUE);
    }

public ResumenPlanificacionGlobal procesarTodosLosOrigenesConLimite(int limitePorOrigen) {
        long startTime = System.currentTimeMillis();

        List<Aeropuerto> Aeropuertos = aeropuertoLoader.cargarAeropuertos();
        List<String> vuelos = cargarVuelos();

        ResumenPlanificacionGlobal resumen = new ResumenPlanificacionGlobal();
        List<PlanificacionResultado> todosLosResultados = new ArrayList<>();
        Map<String, ResumenPlanificacionGlobal.EstadisticaOrigen> statsPorOrigen = new HashMap<>();

        ConfigACO config = new ConfigACO();
        config.antCount = 10;
        config.iterations = 50;

        for (String origen : ORIGENES_DISPONIBLES) {
            Graph graph = graphBuilder.build(Aeropuertos, vuelos);

            List<EnvioDTO> envios = envioLoader.cargarEnvios(origen);

            if (envios.isEmpty()) {
                continue;
            }

            ResumenPlanificacionGlobal.EstadisticaOrigen statsOrigen =
                    new ResumenPlanificacionGlobal.EstadisticaOrigen();
            statsOrigen.origen = origen;

            int count = 0;
            for (EnvioDTO e : envios) {
                if (count >= limitePorOrigen) break;

                PlanificacionResultado resultado;

                try {
                    CostFunction.EnvioContext envio = new CostFunction.EnvioContext(
                            origen, e.destinoICAO, e.cantidadMaletas,
                            e.horaRegistro, e.minutoRegistro
                    );

                    AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
                    aco.run(origen, e.destinoICAO);

                    Ant mejor = aco.getMejorAnt();

                    if (mejor != null && !mejor.path.isEmpty()) {
                        List<String> ruta = mejor.path.stream()
                                .map(n -> n.code)
                                .toList();

                        resultado = new PlanificacionResultado(
                                e.id,
                                origen,
                                e.destinoICAO,
                                e.cantidadMaletas,
                                ruta,
                                mejor.totalCost,
                                true
                        );

                        for (Edge edge : mejor.edgesPath) {
                            edge.useCapacity(e.cantidadMaletas);
                        }

                        statsOrigen.exitosos++;
                        if (mejor.edgesPath.size() == 0) {
                            statsOrigen.rutasDirectas++;
                        } else {
                            statsOrigen.rutasConEscala++;
                        }
                    } else {
                        resultado = PlanificacionResultado.fallido(
                                e.id, origen, e.destinoICAO,
                                "No se encontró ruta válida"
                        );
                        statsOrigen.fallidos++;
                    }
                } catch (Exception ex) {
                    resultado = PlanificacionResultado.fallido(
                            e.id, origen, e.destinoICAO,
                            ex.getMessage()
                    );
                    statsOrigen.fallidos++;
                }

                todosLosResultados.add(resultado);
                statsOrigen.totalEnvios++;
                count++;
            }

            statsPorOrigen.put(origen, statsOrigen);
            System.out.println("Procesados " + count + " envíos desde " + origen);
        }

        resumen.totalEnviosProcesados = todosLosResultados.size();
        resumen.totalEnviosExitosos = (int) todosLosResultados.stream().filter(r -> r.exitoso).count();
        resumen.totalEnviosFallidos = resumen.totalEnviosProcesados - resumen.totalEnviosExitosos;
        resumen.totalMaletas = todosLosResultados.stream().mapToInt(r -> r.cantidadMaletas).sum();
        resumen.tiempoEjecucionMs = System.currentTimeMillis() - startTime;
        resumen.estadisticasPorOrigen = statsPorOrigen;

        double costoTotalExitosos = todosLosResultados.stream()
                .filter(r -> r.exitoso)
                .mapToDouble(r -> r.costoTotal)
                .sum();
        resumen.costoPromedioExitosos = resumen.totalEnviosExitosos > 0
                ? costoTotalExitosos / resumen.totalEnviosExitosos : 0;

        return resumen;
    }

    public List<PlanificacionResultado> procesarTodosLosEnvios(String origen) {
        return procesarConLimite(origen, Integer.MAX_VALUE);
    }

    public List<PlanificacionResultado> procesarConLimite(String origen, int limite) {
        List<Aeropuerto> aeropuertos = aeropuertoLoader.cargarAeropuertos();
        List<String> vuelos = cargarVuelos();
        Graph graph = graphBuilder.build(aeropuertos, vuelos);

        List<EnvioDTO> envios = envioLoader.cargarEnvios(origen);
        List<PlanificacionResultado> resultados = new ArrayList<>();

        ConfigACO config = new ConfigACO();
        config.antCount = 10;
        config.iterations = 50;

        int count = 0;
        for (EnvioDTO e : envios) {
            if (count >= limite) break;
            count++;
            PlanificacionResultado resultado;

            try {
                CostFunction.EnvioContext envio = new CostFunction.EnvioContext(
                        origen, e.destinoICAO, e.cantidadMaletas,
                        e.horaRegistro, e.minutoRegistro
                );

                AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
                aco.run(origen, e.destinoICAO);

                Ant mejor = aco.getMejorAnt();

                if (mejor != null && !mejor.path.isEmpty()) {
                    List<String> ruta = mejor.path.stream()
                            .map(n -> n.code)
                            .toList();

                    resultado = new PlanificacionResultado(
                            e.id,
                            origen,
                            e.destinoICAO,
                            e.cantidadMaletas,
                            ruta,
                            mejor.totalCost,
                            true
                    );

                    for (Edge edge : mejor.edgesPath) {
                        edge.useCapacity(e.cantidadMaletas);
                    }
                } else {
                    resultado = PlanificacionResultado.fallido(
                            e.id, origen, e.destinoICAO,
                            "No se encontró ruta válida"
                    );
                }
            } catch (Exception ex) {
                resultado = PlanificacionResultado.fallido(
                        e.id, origen, e.destinoICAO,
                        ex.getMessage()
                );
            }

            resultados.add(resultado);
        }

        return resultados;
    }

    public String ejecutarACOporEnvio(String origen, int limite) {
        List<Aeropuerto> aeropuertos = aeropuertoLoader.cargarAeropuertos();
        List<String> vuelos = cargarVuelos();
        Graph graph = graphBuilder.build(aeropuertos, vuelos);

        List<EnvioDTO> envios = envioLoader.cargarEnvios(origen);

        int count = 0;
        for (EnvioDTO e : envios) {
            if (count >= limite) break;

ConfigACO config = new ConfigACO();
        config.antCount = 15;
        config.iterations = 30;
        config.alpha = 1.0;
        config.beta = 2.0;

            CostFunction.EnvioContext envio = new CostFunction.EnvioContext(
                    origen, e.destinoICAO, e.cantidadMaletas,
                    e.horaRegistro, e.minutoRegistro
            );

            AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
            aco.run(origen, e.destinoICAO);

            count++;
        }

        return "ACO ejecutado para " + count + " envíos desde " + origen;
    }

    private List<String> cargarVuelos() {
        List<String> vuelos = new ArrayList<>();

        try {
            InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("data/planes_vuelo.txt");

            if (is == null) {
                System.out.println("Archivo de vuelos no encontrado");
                return List.of();
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is));
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