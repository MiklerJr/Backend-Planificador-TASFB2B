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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

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
        return procesarTodosLosOrigenesConLimite(Integer.MAX_VALUE, 5);
    }

    public ResumenPlanificacionGlobal procesarTodosLosOrigenesConLimite(int limitePorOrigen) {
        return procesarTodosLosOrigenesConLimite(limitePorOrigen, 5);
    }

    public ResumenPlanificacionGlobal procesarTodosLosOrigenesConLimite(int limitePorOrigen, int tickMinutosSimulacion) {
        long startTime = System.currentTimeMillis();
        int tick = Math.max(1, tickMinutosSimulacion);

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
            System.out.println("Procesando envíos desde origen: " + origen + " (tick=" + tick + " min)");

            Graph graph = graphBuilder.build(Aeropuertos, vuelos);
            List<EnvioDTO> envios = envioLoader.cargarEnvios(origen);
            if (envios.isEmpty()) {
                continue;
            }

            int limite = (limitePorOrigen <= 0) ? Integer.MAX_VALUE : limitePorOrigen;
            if (envios.size() > limite) {
                envios = envios.subList(0, limite);
            }
            envios = new ArrayList<>(envios);
            envios.sort(Comparator.comparingInt(e -> (e.horaRegistro * 60) + e.minutoRegistro));

            ResumenPlanificacionGlobal.EstadisticaOrigen statsOrigen =
                    new ResumenPlanificacionGlobal.EstadisticaOrigen();
            statsOrigen.origen = origen;

            PriorityQueue<ScheduledEvent> eventos = new PriorityQueue<>(Comparator.comparingInt(ev -> ev.minute));
            List<EnvioDTO> pendientes = new ArrayList<>();

            int idxSiguiente = 0;
            int procesadosOrigen = 0;
            int tiempoActual = envios.get(0).horaRegistro * 60 + envios.get(0).minutoRegistro;

            while (idxSiguiente < envios.size() || !pendientes.isEmpty() || !eventos.isEmpty()) {
                while (!eventos.isEmpty() && eventos.peek().minute <= tiempoActual) {
                    eventos.poll().action.run();
                }

                while (idxSiguiente < envios.size()) {
                    EnvioDTO proximo = envios.get(idxSiguiente);
                    int registroMin = proximo.horaRegistro * 60 + proximo.minutoRegistro;
                    if (registroMin > tiempoActual) {
                        break;
                    }
                    pendientes.add(proximo);
                    idxSiguiente++;
                }

                Iterator<EnvioDTO> itPendientes = pendientes.iterator();
                while (itPendientes.hasNext()) {
                    EnvioDTO e = itPendientes.next();

                    int registroMin = e.horaRegistro * 60 + e.minutoRegistro;
                    CostFunction.EnvioContext envio = new CostFunction.EnvioContext(
                            origen, e.destinoICAO, e.cantidadMaletas,
                            e.horaRegistro, e.minutoRegistro
                    );

                    if (tiempoActual > envio.deadlineMinutos) {
                        statsOrigen.fallidos++;
                        statsOrigen.totalEnvios++;
                        totalEnviosProcesados[0]++;
                        totalMaletas[0] += e.cantidadMaletas;
                        procesadosOrigen++;
                        itPendientes.remove();
                        continue;
                    }

                    if (registroMin > tiempoActual) {
                        continue;
                    }

                    try {
                    AlgorithmACO aco = new AlgorithmACO(graph, config, envio);
                    aco.run(origen, e.destinoICAO);

                    Ant mejor = aco.getMejorAnt();

                    if (mejor != null && !mejor.path.isEmpty() && !mejor.edgesPath.isEmpty()) {
                        Node destinoFinal = graph.nodes.get(e.destinoICAO);
                        if (destinoFinal == null || !destinoFinal.hasStorageCapacity(e.cantidadMaletas)) {
                            statsOrigen.fallidos++;
                            statsOrigen.totalEnvios++;
                            totalEnviosProcesados[0]++;
                            totalMaletas[0] += e.cantidadMaletas;
                            procesadosOrigen++;
                            itPendientes.remove();
                            continue;
                        }

                        destinoFinal.storeLoad(e.cantidadMaletas);
                        int minutosRuta = estimarMinutosRuta(mejor.edgesPath);
                        int liberarDestinoEn = tiempoActual + minutosRuta + CostFunction.TIEMPO_DESTINO_FINAL;
                        eventos.add(new ScheduledEvent(liberarDestinoEn,
                                () -> destinoFinal.releaseLoad(e.cantidadMaletas)));

                        int minutosAcumulados = 0;
                        for (Edge edge : mejor.edgesPath) {
                            edge.useCapacity(e.cantidadMaletas);

                            minutosAcumulados += (int) Math.max(1, Math.round(
                                    CostFunction.calcularDuracionMinutos(edge.departureTime, edge.arrivalTime)
                            ));
                            int liberarEn = tiempoActual + minutosAcumulados;
                            eventos.add(new ScheduledEvent(liberarEn,
                                    () -> edge.usedCapacity = Math.max(0, edge.usedCapacity - e.cantidadMaletas)));
                            minutosAcumulados += CostFunction.TIEMPO_MIN_ESCALA;
                        }

                        statsOrigen.exitosos++;
                        statsOrigen.rutasConEscala++;

                        costoTotalExitosos[0] += mejor.totalCost;
                        totalEnviosExitosos[0]++;

                        statsOrigen.totalEnvios++;
                        totalEnviosProcesados[0]++;
                        totalMaletas[0] += e.cantidadMaletas;
                        procesadosOrigen++;
                        itPendientes.remove();
                    } else {
                        if (tiempoActual + tick > envio.deadlineMinutos) {
                            statsOrigen.fallidos++;
                            statsOrigen.totalEnvios++;
                            totalEnviosProcesados[0]++;
                            totalMaletas[0] += e.cantidadMaletas;
                            procesadosOrigen++;
                            itPendientes.remove();
                        }
                    }
                } catch (Exception ex) {
                    statsOrigen.fallidos++;
                    statsOrigen.totalEnvios++;
                    totalEnviosProcesados[0]++;
                    totalMaletas[0] += e.cantidadMaletas;
                    procesadosOrigen++;
                    itPendientes.remove();
                }
                }

                if (procesadosOrigen % 10000 == 0 && procesadosOrigen > 0) {
                    System.out.println("Origen " + origen + ": procesados " + procesadosOrigen + " envíos...");
                }

                tiempoActual += tick;
            }

            statsPorOrigen.put(origen, statsOrigen);
            System.out.println("Procesados " + procesadosOrigen + " envíos desde " + origen);
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

    private int estimarMinutosRuta(List<Edge> edgesPath) {
        if (edgesPath == null || edgesPath.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < edgesPath.size(); i++) {
            Edge edge = edgesPath.get(i);
            total += (int) Math.max(1, Math.round(
                    CostFunction.calcularDuracionMinutos(edge.departureTime, edge.arrivalTime)
            ));
            if (i < edgesPath.size() - 1) {
                total += CostFunction.TIEMPO_MIN_ESCALA;
            }
        }
        return total;
    }

    private static class ScheduledEvent {
        final int minute;
        final Runnable action;

        ScheduledEvent(int minute, Runnable action) {
            this.minute = minute;
            this.action = action;
        }
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
