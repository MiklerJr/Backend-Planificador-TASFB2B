package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.AlgorithmACO;
import com.tasfb2b.planificador.algorithm.aco.Ant;
import com.tasfb2b.planificador.algorithm.aco.ConfigACO;
import com.tasfb2b.planificador.algorithm.aco.CostFunction;
import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.aco.Node;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

@Service
public class PlanificadorService {

    private final AeropuertoLoader aeropuertoLoader;
    private final GraphBuilder graphBuilder;
    private final EnvioLoader envioLoader;

    private static final int DEFAULT_TICK_MINUTES = 5;

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
        return procesarTodosLosOrigenesConLimite(Integer.MAX_VALUE, DEFAULT_TICK_MINUTES);
    }

    public ResumenPlanificacionGlobal procesarTodosLosOrigenesConLimite(int limitePorOrigen) {
        return procesarTodosLosOrigenesConLimite(limitePorOrigen, DEFAULT_TICK_MINUTES);
    }

    public ResumenPlanificacionGlobal procesarTodosLosOrigenesConLimite(int limitePorOrigen, int tickMinutosSimulacion) {
        PlanRequest request = PlanRequest.todos(limitePorOrigen, tickMinutosSimulacion);
        return procesarBase(request);
    }

    public List<PlanificacionResultado> procesarTodosLosEnvios(String origen) {
        PlanRequest request = PlanRequest.unOrigen(origen, Integer.MAX_VALUE, DEFAULT_TICK_MINUTES);
        return procesarBaseConResultados(request).resultados;
    }

    public List<PlanificacionResultado> procesarConLimite(String origen, int limite) {
        PlanRequest request = PlanRequest.unOrigen(origen, limite, DEFAULT_TICK_MINUTES);
        return procesarBaseConResultados(request).resultados;
    }

    public String ejecutarACOporEnvio(String origen, int limite) {
        PlanRequest request = PlanRequest.unOrigen(origen, limite, DEFAULT_TICK_MINUTES);
        ResumenPlanificacionGlobal resumen = procesarBase(request);
        return "ACO ejecutado para " + resumen.totalEnviosProcesados + " envíos desde " + origen;
    }

    public List<PlanificacionResultado> procesarTodosLosOrigenesConResultados(int limitePorOrigen, int tickMinutosSimulacion) {
        PlanRequest request = PlanRequest.todos(limitePorOrigen, tickMinutosSimulacion);
        return procesarBaseConResultados(request).resultados;
    }

    private ResumenPlanificacionGlobal procesarBase(PlanRequest request) {
        return procesarBaseConResultados(request).resumen;
    }

    private BaseRunResult procesarBaseConResultados(PlanRequest request) {
        long startTime = System.currentTimeMillis();

        int tick = Math.max(1, request.tickMinutosSimulacion);
        int limite = (request.limitePorOrigen <= 0) ? Integer.MAX_VALUE : request.limitePorOrigen;

        List<Aeropuerto> aeropuertos = aeropuertoLoader.cargarAeropuertos();
        List<String> vuelos = cargarVuelos();

        ResumenPlanificacionGlobal resumen = new ResumenPlanificacionGlobal();
        resumen.estadisticasPorOrigen = new HashMap<>();
        List<PlanificacionResultado> resultados = new ArrayList<>();

        ConfigACO config = new ConfigACO();
        config.antCount = 10;
        config.iterations = 50;

        for (String origen : request.origenes) {
            Graph graph = graphBuilder.build(aeropuertos, vuelos);
            List<EnvioDTO> envios = envioLoader.cargarEnvios(origen);
            if (envios.isEmpty()) {
                continue;
            }

            if (envios.size() > limite) {
                envios = envios.subList(0, limite);
            }
            envios = new ArrayList<>(envios);
            envios.sort(Comparator.comparingInt(e -> (e.horaRegistro * 60) + e.minutoRegistro));

            ResumenPlanificacionGlobal.EstadisticaOrigen stats = new ResumenPlanificacionGlobal.EstadisticaOrigen();
            stats.origen = origen;

            PriorityQueue<ScheduledEvent> eventos = new PriorityQueue<>(Comparator.comparingInt(ev -> ev.minute));
            List<EnvioDTO> pendientes = new ArrayList<>();

            int idxSiguiente = 0;
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

                Iterator<EnvioDTO> it = pendientes.iterator();
                while (it.hasNext()) {
                    EnvioDTO envio = it.next();

                    if (request.destinosPermitidos != null
                            && !request.destinosPermitidos.contains(envio.destinoICAO)) {
                        continue;
                    }

                    PlanificacionResultado resultado = intentarPlanificarEnvio(
                            origen, envio, graph, config, eventos, tiempoActual, tick
                    );

                    if (resultado == null) {
                        continue;
                    }

                    resultados.add(resultado);
                    stats.totalEnvios++;
                    resumen.totalEnviosProcesados++;
                    resumen.totalMaletas += envio.cantidadMaletas;

                    if (resultado.exitoso) {
                        stats.exitosos++;
                        stats.rutasConEscala++;
                        resumen.totalEnviosExitosos++;
                        resumen.costoPromedioExitosos += resultado.costoTotal;
                    } else {
                        stats.fallidos++;
                    }

                    it.remove();
                }

                tiempoActual += tick;
            }

            resumen.estadisticasPorOrigen.put(origen, stats);
        }

        resumen.totalEnviosFallidos = resumen.totalEnviosProcesados - resumen.totalEnviosExitosos;
        resumen.tiempoEjecucionMs = System.currentTimeMillis() - startTime;
        if (resumen.totalEnviosExitosos > 0) {
            resumen.costoPromedioExitosos = resumen.costoPromedioExitosos / resumen.totalEnviosExitosos;
        }

        return new BaseRunResult(resumen, resultados);
    }

    private PlanificacionResultado intentarPlanificarEnvio(
            String origen,
            EnvioDTO e,
            Graph graph,
            ConfigACO config,
            PriorityQueue<ScheduledEvent> eventos,
            int tiempoActual,
            int tick
    ) {
        CostFunction.EnvioContext ctx = new CostFunction.EnvioContext(
                origen, e.destinoICAO, e.cantidadMaletas, e.horaRegistro, e.minutoRegistro
        );

        if (tiempoActual > ctx.deadlineMinutos) {
            return PlanificacionResultado.fallido(e.id, origen, e.destinoICAO, "Deadline excedido");
        }

        try {
            AlgorithmACO aco = new AlgorithmACO(graph, config, ctx);
            aco.run(origen, e.destinoICAO);
            Ant mejor = aco.getMejorAnt();

            if (mejor != null && !mejor.path.isEmpty() && !mejor.edgesPath.isEmpty()) {
                Node destinoFinal = graph.nodes.get(e.destinoICAO);
                if (destinoFinal == null || !destinoFinal.hasStorageCapacity(e.cantidadMaletas)) {
                    return PlanificacionResultado.fallido(
                            e.id, origen, e.destinoICAO, "Sin capacidad de almacenamiento en destino"
                    );
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

                List<String> ruta = mejor.path.stream().map(n -> n.code).toList();
                return new PlanificacionResultado(
                        e.id, origen, e.destinoICAO, e.cantidadMaletas, ruta, mejor.totalCost, true
                );
            }

            if (tiempoActual + tick > ctx.deadlineMinutos) {
                return PlanificacionResultado.fallido(e.id, origen, e.destinoICAO, "No se encontró ruta válida");
            }

            return null;
        } catch (Exception ex) {
            return PlanificacionResultado.fallido(e.id, origen, e.destinoICAO, ex.getMessage());
        }
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

    private static class PlanRequest {
        final List<String> origenes;
        final int limitePorOrigen;
        final int tickMinutosSimulacion;
        final Set<String> destinosPermitidos;

        private PlanRequest(List<String> origenes,
                            int limitePorOrigen,
                            int tickMinutosSimulacion,
                            Set<String> destinosPermitidos) {
            this.origenes = origenes;
            this.limitePorOrigen = limitePorOrigen;
            this.tickMinutosSimulacion = tickMinutosSimulacion;
            this.destinosPermitidos = destinosPermitidos;
        }

        static PlanRequest todos(int limitePorOrigen, int tickMinutosSimulacion) {
            return new PlanRequest(List.of(ORIGENES_DISPONIBLES), limitePorOrigen, tickMinutosSimulacion, null);
        }

        static PlanRequest unOrigen(String origen, int limitePorOrigen, int tickMinutosSimulacion) {
            return new PlanRequest(List.of(origen), limitePorOrigen, tickMinutosSimulacion, null);
        }

        static PlanRequest unDestino(String destino, int limitePorOrigen, int tickMinutosSimulacion) {
            return new PlanRequest(List.of(ORIGENES_DISPONIBLES), limitePorOrigen, tickMinutosSimulacion,
                    new HashSet<>(Set.of(destino)));
        }
    }

    private static class ScheduledEvent {
        final int minute;
        final Runnable action;

        ScheduledEvent(int minute, Runnable action) {
            this.minute = minute;
            this.action = action;
        }
    }

    private static class BaseRunResult {
        final ResumenPlanificacionGlobal resumen;
        final List<PlanificacionResultado> resultados;

        BaseRunResult(ResumenPlanificacionGlobal resumen, List<PlanificacionResultado> resultados) {
            this.resumen = resumen;
            this.resultados = resultados;
        }
    }
}
