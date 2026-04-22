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
        Map<String, ResumenPlanificacionGlobal.EstadisticaOrigen> statsPorOrigen = new HashMap<>();
        final int[] totalEnviosProcesados = {0};
        final int[] totalEnviosExitosos = {0};
        final int[] totalMaletas = {0};
        final double[] costoTotalExitosos = {0.0};

        ConfigACO config = new ConfigACO();
        config.antCount = 10;
        config.iterations = 50;

        for (String origen : ORIGENES_DISPONIBLES) {
            System.out.println("Procesando envíos desde origen: " + origen);

            Graph graph = graphBuilder.build(Aeropuertos, vuelos);

            ResumenPlanificacionGlobal.EstadisticaOrigen statsOrigen =
                    new ResumenPlanificacionGlobal.EstadisticaOrigen();
            statsOrigen.origen = origen;

            final int[] count = {0};
            final int limite = (limitePorOrigen <= 0) ? Integer.MAX_VALUE : limitePorOrigen;

            envioLoader.procesarEnvios(origen, limite, e -> {
                try {
                    CostFunction.EnvioContext envio = new CostFunction.EnvioContext(
                            origen, e.destinoICAO, e.cantidadMaletas,
                            e.horaRegistro, e.minutoRegistro
                    );

                    AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
                    aco.run(origen, e.destinoICAO);

                    Ant mejor = aco.getMejorAnt();

                    if (mejor != null && !mejor.path.isEmpty()) {
                        for (Edge edge : mejor.edgesPath) {
                            edge.useCapacity(e.cantidadMaletas);
                        }

                        statsOrigen.exitosos++;
                        if (mejor.edgesPath.isEmpty()) {
                            statsOrigen.rutasDirectas++;
                        } else {
                            statsOrigen.rutasConEscala++;
                        }

                        costoTotalExitosos[0] += mejor.totalCost;
                        totalEnviosExitosos[0]++;
                    } else {
                        statsOrigen.fallidos++;
                    }
                } catch (Exception ex) {
                    statsOrigen.fallidos++;
                }

                statsOrigen.totalEnvios++;
                totalEnviosProcesados[0]++;
                totalMaletas[0] += e.cantidadMaletas;
                count[0]++;

                if (count[0] % 10000 == 0) {
                    System.out.println("Origen " + origen + ": procesados " + count[0] + " envíos...");
                }
            });

            statsPorOrigen.put(origen, statsOrigen);
            System.out.println("Procesados " + count[0] + " envíos desde " + origen);
        }

        resumen.totalEnviosProcesados = totalEnviosProcesados[0];
        resumen.totalEnviosExitosos = totalEnviosExitosos[0];
        resumen.totalEnviosFallidos = resumen.totalEnviosProcesados - resumen.totalEnviosExitosos;
        resumen.totalMaletas = totalMaletas[0];
        resumen.tiempoEjecucionMs = System.currentTimeMillis() - startTime;
        resumen.estadisticasPorOrigen = statsPorOrigen;

        resumen.costoPromedioExitosos = resumen.totalEnviosExitosos > 0
                ? costoTotalExitosos[0] / resumen.totalEnviosExitosos : 0;

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
