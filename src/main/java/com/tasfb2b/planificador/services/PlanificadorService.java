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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
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

    public String exportarAuditoriaCsv(int limitePorOrigen, int tickMinutosSimulacion, int sampleSize, String outputPath) {
        PlanRequest request = PlanRequest.todos(limitePorOrigen, tickMinutosSimulacion);
        BaseRunResult run = procesarBaseConResultados(request);

        List<AuditRecord> registros = sampleAudit(run.auditoria, sampleSize);
        String csv = construirCsvAuditoria(registros);
        Path out = Path.of(outputPath);

        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, csv);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo escribir auditoría CSV en " + outputPath, e);
        }

        return out.toAbsolutePath().toString();
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
        List<AuditRecord> auditoria = new ArrayList<>();

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

                    AttemptResult intento = intentarPlanificarEnvio(
                            origen, envio, graph, config, eventos, tiempoActual, tick
                    );

                    if (intento == null) {
                        continue;
                    }

                    PlanificacionResultado resultado = intento.resultado;

                    resultados.add(resultado);
                    auditoria.add(intento.auditRecord);
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

        return new BaseRunResult(resumen, resultados, auditoria);
    }

    private AttemptResult intentarPlanificarEnvio(
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
            PlanificacionResultado r = PlanificacionResultado.fallido(e.id, origen, e.destinoICAO, "Deadline excedido");
            return new AttemptResult(r, AuditRecord.fallido(origen, e, ctx.deadlineMinutos, "Deadline excedido", tiempoActual));
        }

        try {
            AlgorithmACO aco = new AlgorithmACO(graph, config, ctx);
            aco.run(origen, e.destinoICAO);
            Ant mejor = aco.getMejorAnt();

            if (mejor != null && !mejor.path.isEmpty() && !mejor.edgesPath.isEmpty()) {
                if (!e.destinoICAO.equals(mejor.path.get(mejor.path.size() - 1).code)) {
                    if (tiempoActual + tick > ctx.deadlineMinutos) {
                        PlanificacionResultado r = PlanificacionResultado.fallido(
                                e.id, origen, e.destinoICAO, "Ruta incompleta: no llega al destino"
                        );
                        return new AttemptResult(r, AuditRecord.fallido(
                                origen, e, ctx.deadlineMinutos,
                                "Ruta incompleta: no llega al destino", tiempoActual
                        ));
                    }
                    return null;
                }

                Node destinoFinal = graph.nodes.get(e.destinoICAO);
                if (destinoFinal == null || !destinoFinal.hasStorageCapacity(e.cantidadMaletas)) {
                    PlanificacionResultado r = PlanificacionResultado.fallido(
                            e.id, origen, e.destinoICAO, "Sin capacidad de almacenamiento en destino");
                    return new AttemptResult(r, AuditRecord.fallido(origen, e, ctx.deadlineMinutos,
                            "Sin capacidad de almacenamiento en destino", tiempoActual));
                }

                boolean escalaMinOk = cumpleEscalaMinima(mejor.edgesPath);
                int tiempoVueloMin = calcularTiempoVueloMin(mejor.edgesPath);
                int tiempoEsperaMin = calcularTiempoEsperaMin(mejor.edgesPath);
                int tiempoTotalMin = tiempoVueloMin + tiempoEsperaMin;
                int llegadaMin = ctx.minutosRegistro + tiempoTotalMin;
                int slack = ctx.deadlineMinutos - llegadaMin;
                boolean sinDirecto = mejor.edgesPath.size() > 1;
                boolean sinCiclos = sinCiclos(mejor.path);
                boolean capacidadVuelosOk = mejor.edgesPath.stream().allMatch(ed -> ed.hasCapacity(e.cantidadMaletas));
                boolean cumpleSLA = llegadaMin <= ctx.deadlineMinutos;
                int score = calcularScore(sinDirecto, sinCiclos, escalaMinOk, capacidadVuelosOk, true, cumpleSLA,
                        mejor.edgesPath.size() - 1, tiempoEsperaMin, slack);

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
                PlanificacionResultado r = new PlanificacionResultado(
                        e.id, origen, e.destinoICAO, e.cantidadMaletas, ruta, mejor.totalCost, true
                );
                AuditRecord audit = AuditRecord.exitoso(origen, e, ctx.deadlineMinutos, ruta, mejor.totalCost,
                        mejor.edgesPath.size(), tiempoVueloMin, tiempoEsperaMin, tiempoTotalMin, llegadaMin, slack,
                        cumpleSLA, sinCiclos, sinDirecto, escalaMinOk, capacidadVuelosOk, true, score);
                return new AttemptResult(r, audit);
            }

            if (tiempoActual + tick > ctx.deadlineMinutos) {
                PlanificacionResultado r = PlanificacionResultado.fallido(e.id, origen, e.destinoICAO, "No se encontró ruta válida");
                return new AttemptResult(r, AuditRecord.fallido(origen, e, ctx.deadlineMinutos,
                        "No se encontró ruta válida", tiempoActual));
            }

            return null;
        } catch (Exception ex) {
            PlanificacionResultado r = PlanificacionResultado.fallido(e.id, origen, e.destinoICAO, ex.getMessage());
            return new AttemptResult(r, AuditRecord.fallido(origen, e, ctx.deadlineMinutos, ex.getMessage(), tiempoActual));
        }
    }

    private boolean cumpleEscalaMinima(List<Edge> edgesPath) {
        for (int i = 0; i < edgesPath.size() - 1; i++) {
            if (!CostFunction.tieneTiempoMinimoEscala(edgesPath.get(i), edgesPath.get(i + 1))) {
                return false;
            }
        }
        return true;
    }

    private int calcularTiempoVueloMin(List<Edge> edgesPath) {
        int total = 0;
        for (Edge edge : edgesPath) {
            total += (int) Math.max(1, Math.round(
                    CostFunction.calcularDuracionMinutos(edge.departureTime, edge.arrivalTime)
            ));
        }
        return total;
    }

    private int calcularTiempoEsperaMin(List<Edge> edgesPath) {
        int espera = 0;
        for (int i = 0; i < edgesPath.size() - 1; i++) {
            int llegada = parsearMinutos(edgesPath.get(i).arrivalTime);
            int salida = parsearMinutos(edgesPath.get(i + 1).departureTime);
            int diff = salida - llegada;
            if (diff < 0) {
                diff += 1440;
            }
            espera += diff;
        }
        return espera;
    }

    private int parsearMinutos(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private boolean sinCiclos(List<Node> path) {
        Set<String> visited = new HashSet<>();
        for (Node node : path) {
            if (!visited.add(node.code)) {
                return false;
            }
        }
        return true;
    }

    private int calcularScore(boolean sinDirecto,
                              boolean sinCiclos,
                              boolean escalaMinOk,
                              boolean capacidadVuelosOk,
                              boolean almacenDestinoOk,
                              boolean cumpleSLA,
                              int escalas,
                              int tiempoEsperaMin,
                              int slackSlaMin) {
        if (!sinDirecto || !sinCiclos || !escalaMinOk || !capacidadVuelosOk || !almacenDestinoOk || !cumpleSLA) {
            return 0;
        }
        double score = 100.0;
        int excesoEscalas = Math.max(0, escalas - 2);
        score -= excesoEscalas * 15.0;
        score -= tiempoEsperaMin * 0.05;
        if (slackSlaMin < 60) {
            score -= 20.0;
        }
        return (int) Math.max(0, Math.round(score));
    }

    private List<AuditRecord> sampleAudit(List<AuditRecord> base, int sampleSize) {
        if (sampleSize <= 0 || sampleSize >= base.size()) {
            return base;
        }

        List<AuditRecord> exitos = new ArrayList<>();
        List<AuditRecord> fallos = new ArrayList<>();
        for (AuditRecord r : base) {
            if (r.exitoso) {
                exitos.add(r);
            } else {
                fallos.add(r);
            }
        }

        Collections.shuffle(exitos, new Random(42));
        Collections.shuffle(fallos, new Random(42));

        int takeExitos = Math.min(exitos.size(), sampleSize / 2);
        int takeFallos = Math.min(fallos.size(), sampleSize - takeExitos);

        List<AuditRecord> out = new ArrayList<>();
        out.addAll(exitos.subList(0, takeExitos));
        out.addAll(fallos.subList(0, takeFallos));

        int faltantes = sampleSize - out.size();
        if (faltantes > 0) {
            List<AuditRecord> pool = new ArrayList<>(base);
            Collections.shuffle(pool, new Random(43));
            for (AuditRecord r : pool) {
                if (faltantes == 0) {
                    break;
                }
                if (!out.contains(r)) {
                    out.add(r);
                    faltantes--;
                }
            }
        }
        return out;
    }

    private String construirCsvAuditoria(List<AuditRecord> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("idEnvio,origen,destino,registroHHMM,deadlineMin,exitoso,motivoFalla,ruta,numTramos,numEscalas,")
          .append("tiempoVueloMin,tiempoEsperaMin,tiempoTotalMin,llegadaMin,slackSlaMin,costoTotal,")
          .append("cumpleSLA,sinCiclos,sinDirecto,escalaMinOK,capacidadVuelosOK,almacenDestinoOK,scoreCalidad\n");
        for (AuditRecord r : rows) {
            sb.append(csv(r.idEnvio)).append(',')
              .append(csv(r.origen)).append(',')
              .append(csv(r.destino)).append(',')
              .append(csv(r.registroHHMM)).append(',')
              .append(r.deadlineMin).append(',')
              .append(r.exitoso).append(',')
              .append(csv(r.motivoFalla)).append(',')
              .append(csv(r.ruta)).append(',')
              .append(r.numTramos).append(',')
              .append(r.numEscalas).append(',')
              .append(r.tiempoVueloMin).append(',')
              .append(r.tiempoEsperaMin).append(',')
              .append(r.tiempoTotalMin).append(',')
              .append(r.llegadaMin).append(',')
              .append(r.slackSlaMin).append(',')
              .append(r.costoTotal).append(',')
              .append(r.cumpleSla).append(',')
              .append(r.sinCiclos).append(',')
              .append(r.sinDirecto).append(',')
              .append(r.escalaMinOk).append(',')
              .append(r.capacidadVuelosOk).append(',')
              .append(r.almacenDestinoOk).append(',')
              .append(r.scoreCalidad)
              .append('\n');
        }
        return sb.toString();
    }

    private String csv(String v) {
        if (v == null) {
            return "";
        }
        String escaped = v.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
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
        final List<AuditRecord> auditoria;

        BaseRunResult(ResumenPlanificacionGlobal resumen,
                      List<PlanificacionResultado> resultados,
                      List<AuditRecord> auditoria) {
            this.resumen = resumen;
            this.resultados = resultados;
            this.auditoria = auditoria;
        }
    }

    private static class AttemptResult {
        final PlanificacionResultado resultado;
        final AuditRecord auditRecord;

        AttemptResult(PlanificacionResultado resultado, AuditRecord auditRecord) {
            this.resultado = resultado;
            this.auditRecord = auditRecord;
        }
    }

    private static class AuditRecord {
        final String idEnvio;
        final String origen;
        final String destino;
        final String registroHHMM;
        final int deadlineMin;
        final boolean exitoso;
        final String motivoFalla;
        final String ruta;
        final int numTramos;
        final int numEscalas;
        final int tiempoVueloMin;
        final int tiempoEsperaMin;
        final int tiempoTotalMin;
        final int llegadaMin;
        final int slackSlaMin;
        final double costoTotal;
        final boolean cumpleSla;
        final boolean sinCiclos;
        final boolean sinDirecto;
        final boolean escalaMinOk;
        final boolean capacidadVuelosOk;
        final boolean almacenDestinoOk;
        final int scoreCalidad;

        private AuditRecord(String idEnvio,
                            String origen,
                            String destino,
                            String registroHHMM,
                            int deadlineMin,
                            boolean exitoso,
                            String motivoFalla,
                            String ruta,
                            int numTramos,
                            int numEscalas,
                            int tiempoVueloMin,
                            int tiempoEsperaMin,
                            int tiempoTotalMin,
                            int llegadaMin,
                            int slackSlaMin,
                            double costoTotal,
                            boolean cumpleSla,
                            boolean sinCiclos,
                            boolean sinDirecto,
                            boolean escalaMinOk,
                            boolean capacidadVuelosOk,
                            boolean almacenDestinoOk,
                            int scoreCalidad) {
            this.idEnvio = idEnvio;
            this.origen = origen;
            this.destino = destino;
            this.registroHHMM = registroHHMM;
            this.deadlineMin = deadlineMin;
            this.exitoso = exitoso;
            this.motivoFalla = motivoFalla;
            this.ruta = ruta;
            this.numTramos = numTramos;
            this.numEscalas = numEscalas;
            this.tiempoVueloMin = tiempoVueloMin;
            this.tiempoEsperaMin = tiempoEsperaMin;
            this.tiempoTotalMin = tiempoTotalMin;
            this.llegadaMin = llegadaMin;
            this.slackSlaMin = slackSlaMin;
            this.costoTotal = costoTotal;
            this.cumpleSla = cumpleSla;
            this.sinCiclos = sinCiclos;
            this.sinDirecto = sinDirecto;
            this.escalaMinOk = escalaMinOk;
            this.capacidadVuelosOk = capacidadVuelosOk;
            this.almacenDestinoOk = almacenDestinoOk;
            this.scoreCalidad = scoreCalidad;
        }

        static AuditRecord exitoso(String origen,
                                   EnvioDTO e,
                                   int deadlineMin,
                                   List<String> ruta,
                                   double costoTotal,
                                   int numTramos,
                                   int tiempoVueloMin,
                                   int tiempoEsperaMin,
                                   int tiempoTotalMin,
                                   int llegadaMin,
                                   int slackSlaMin,
                                   boolean cumpleSla,
                                   boolean sinCiclos,
                                   boolean sinDirecto,
                                   boolean escalaMinOk,
                                   boolean capacidadVuelosOk,
                                   boolean almacenDestinoOk,
                                   int scoreCalidad) {
            return new AuditRecord(
                    e.id,
                    origen,
                    e.destinoICAO,
                    String.format("%02d:%02d", e.horaRegistro, e.minutoRegistro),
                    deadlineMin,
                    true,
                    "",
                    String.join("->", ruta),
                    numTramos,
                    Math.max(0, numTramos - 1),
                    tiempoVueloMin,
                    tiempoEsperaMin,
                    tiempoTotalMin,
                    llegadaMin,
                    slackSlaMin,
                    costoTotal,
                    cumpleSla,
                    sinCiclos,
                    sinDirecto,
                    escalaMinOk,
                    capacidadVuelosOk,
                    almacenDestinoOk,
                    scoreCalidad
            );
        }

        static AuditRecord fallido(String origen,
                                   EnvioDTO e,
                                   int deadlineMin,
                                   String motivo,
                                   int tiempoActual) {
            return new AuditRecord(
                    e.id,
                    origen,
                    e.destinoICAO,
                    String.format("%02d:%02d", e.horaRegistro, e.minutoRegistro),
                    deadlineMin,
                    false,
                    motivo,
                    "",
                    0,
                    0,
                    0,
                    0,
                    0,
                    tiempoActual,
                    deadlineMin - tiempoActual,
                    0.0,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    0
            );
        }
    }
}
