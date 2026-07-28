package com.tasfb2b.planificador.servicios;


import com.tasfb2b.planificador.algoritmo.alns.PreColapso;

import com.tasfb2b.planificador.algoritmo.alns.CodificadorClaveVuelo;
import com.tasfb2b.planificador.algoritmo.alns.GestorBacklog;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.dto.almacenes.*;
import com.tasfb2b.planificador.dto.datos.AeropuertoDTO;
import com.tasfb2b.planificador.dto.jobs.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;
import com.tasfb2b.planificador.servicios.persistencia.LectorSolucionBd;
import com.tasfb2b.planificador.servicios.persistencia.PersistenciaSolucionService;
import com.tasfb2b.planificador.utilidades.CalculadorEstadoEnvio;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.FormatoSimulacion;
import com.tasfb2b.planificador.utilidades.FragmentadorEnvios;
import com.tasfb2b.planificador.utilidades.MapeadorAlgoritmo;
import com.tasfb2b.planificador.utilidades.analizador.AnalizadorVuelos;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TelemetriaSimulacionService {

    private final RegistroJobs jobs;
    private final CacheOffsetsAeropuerto cacheOffsets;
    private final PersistenciaSolucionService persistencia;
    private final LectorSolucionBd solucionBdReader;
    private final MotorGrafoCache motorCache;
    private final MapeadorAlgoritmo mapper;
    private final CargadorDatos cargadorDatos;
    private final PlanificadorProperties props;

    @org.springframework.beans.factory.annotation.Autowired
    public TelemetriaSimulacionService(RegistroJobs jobs,
                                       CacheOffsetsAeropuerto cacheOffsets,
                                       PersistenciaSolucionService persistencia,
                                       LectorSolucionBd solucionBdReader,
                                       MotorGrafoCache motorCache,
                                       MapeadorAlgoritmo mapper,
                                       CargadorDatos cargadorDatos,
                                       PlanificadorProperties props) {
        this.jobs = jobs;
        this.cacheOffsets = cacheOffsets;
        this.persistencia = persistencia;
        this.solucionBdReader = solucionBdReader;
        this.motorCache = motorCache;
        this.mapper = mapper;
        this.cargadorDatos = cargadorDatos;
        this.props = props;
    }

    TelemetriaSimulacionService() {
        this(null, new CacheOffsetsAeropuerto(null), null, null, null, null, null, null);
    }

    TelemetriaSimulacionService(RegistroJobs jobs) {
        this(jobs, new CacheOffsetsAeropuerto(null), null, null, null, null, null, null);
    }


    public SerieAlmacenesResponse getSerieAlmacenes(String jobId, int desde) {
        EstadoJob job = jobs.get(jobId);
        if (job == null) return null;

        int desdeNorm = Math.max(0, desde);
        List<List<OcupacionAlmacenSlot>> series = job.seriesDesdeExacto(desdeNorm);
        List<SerieAlmacenesResponse.SerieItem> filas = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            SerieAlmacenesResponse.SerieItem item = new SerieAlmacenesResponse.SerieItem();
            item.setBloqueIdx(desdeNorm + i);
            item.setSlots(series.get(i));
            filas.add(item);
        }

        SerieAlmacenesResponse body = new SerieAlmacenesResponse();
        body.setJobId(job.getJobId());
        body.setDesde(desdeNorm);
        body.setTotal(job.seriesPublicadas());
        body.setPrimeraSerieDisponible(job.primeraSerieDisponible());
        body.setTerminado(!"encolado".equals(job.estado)
                && !"calentando".equals(job.estado)
                && !"ejecutando".equals(job.estado));
        body.setSeries(filas);
        return body;
    }

    public EstadoInicialResponse construirEstadoInicialResponse(EstadoJob job) {
        EstadoInicialResponse body = new EstadoInicialResponse();
        body.setJobId(job.getJobId());
        if (job.fechaInicio != null) body.setFechaInicio(job.fechaInicio.toString());
        List<AsignacionMaleta> snapshot = job.estadoInicial;
        body.setTotal(snapshot.size());
        body.setAsignaciones(snapshot);
        return body;
    }

    public EstadoJobResponse getEstadoJob(String jobId) {
        EstadoJob job = jobs.get(jobId);
        if (job == null) return null;

        EstadoJobResponse body = new EstadoJobResponse();
        body.setJobId(job.getJobId());
        body.setEscenario(job.getEscenario());
        body.setAlgoritmo(job.algoritmo);
        body.setSeed(job.seed);
        if (job.fechaInicio != null) body.setFechaInicio(job.fechaInicio.toString());
        body.setK(job.getK());
        body.setEstado(job.estado);
        body.setBloqueActual(job.bloqueActual);
        body.setTotalBloques(job.totalBloques);
        body.setProgreso(job.getProgreso());
        body.setBloqueWarmup(job.bloqueWarmup);
        body.setTotalBloquesWarmup(job.totalBloquesWarmup);
        body.setProgresoWarmup(job.getProgresoWarmup());
        body.setPosicionEnCola(jobs.posicionEnCola(jobId));
        body.setCanceladoPorUsuario(job.canceladoPorUsuario);
        body.setTaPromedioMs(job.taPromedioMs);
        if (job.primerBloqueRealMs != null) {
            body.setTemporizadorInicioUtc(java.time.Instant.ofEpochMilli(job.primerBloqueRealMs).toString());
        }
        body.setDuracionRealMs(job.getDuracionRealMs());
        body.setInicio(job.inicio.toString());
        if (job.fin != null) body.setFin(job.fin.toString());
        if (job.error != null) body.setError(job.error);
        if (job.alertaColapso != null) body.setAlertaColapso(job.alertaColapso);
        body.setVuelosCancelados(job.getVuelosCancelados());
        body.setCancelacionesNoAplicadas(job.getCancelacionesNoAplicadas());
        body.setEnviosInyectados(job.getEnviosInyectados());
        body.setVuelosAgregados(job.getVuelosAgregados());
        body.setAltasVueloNoAplicadas(job.getAltasVueloNoAplicadas());
        body.setAeropuertosAgregados(job.getAeropuertosAgregados());
        body.setAltasAeropuertoNoAplicadas(job.getAltasAeropuertoNoAplicadas());
        return body;
    }

    public EnvioEstadoResponse buscarEstadoEnvio(String jobId, String idEnvio, LocalDateTime instante) {
        List<AsignacionMaleta> asigs = construirAsignacionesDesdeBd(jobId, idEnvio);
        if (asigs.isEmpty()) asigs = construirAsignacionesSinteticas(jobId, idEnvio);
        if (asigs.isEmpty()) return null;
        LocalDateTime ahora = (instante != null) ? instante : ahoraDelJob(jobId);
        EnvioEstadoResponse resp = asigs.size() == 1
                ? CalculadorEstadoEnvio.calcular(asigs.get(0), ahora)
                : CalculadorEstadoEnvio.agregarFragmentos(asigs, ahora);
        resp.setInstanteDerivadoDelJob(instante == null && ahora != null);
        return resp;
    }

    private List<AsignacionMaleta> construirAsignacionesSinteticas(String jobId, String idEnvio) {
        if (idEnvio == null || !idEnvio.startsWith("INV-")) return List.of();
        EstadoJob job = jobs.get(jobId);
        if (job != null) {
            List<AsignacionMaleta> enRam = job.getRutasSinteticasFamilia(idEnvio);
            if (!enRam.isEmpty()) return ordenarPorFragmento(enRam);
        }
        if (!persistencia.reflejaEnBd(jobId)) return List.of();
        Grafo graph = motorCache.obtenerGrafo(
                () -> mapper.mapearAGrafo(cargadorDatos.getAeropuertos(), cargadorDatos.getVuelos()));
        Map<String, Arista> indiceVuelo = solucionBdReader.construirIndiceVuelo(graph);
        return ordenarPorFragmento(solucionBdReader.buscarPorEnvioInyectado(idEnvio, indiceVuelo).stream()
                .map(b -> buildAsignaciones(List.of(b)).get(0))
                .collect(Collectors.toList()));
    }

    private List<AsignacionMaleta> construirAsignacionesDesdeBd(String jobId, String idEnvio) {
        if (!persistencia.reflejaEnBd(jobId)) return List.of();
        Grafo graph = motorCache.obtenerGrafo(
                () -> mapper.mapearAGrafo(cargadorDatos.getAeropuertos(), cargadorDatos.getVuelos()));
        Map<String, Arista> indiceVuelo = solucionBdReader.construirIndiceVuelo(graph);
        return ordenarPorFragmento(solucionBdReader.buscarPorEnvio(idEnvio, indiceVuelo).stream()
                .map(b -> buildAsignaciones(List.of(b)).get(0))
                .collect(Collectors.toList()));
    }

    private static List<AsignacionMaleta> ordenarPorFragmento(List<AsignacionMaleta> asigs) {
        asigs.sort(Comparator.comparingInt(a -> FragmentadorEnvios.numeroFragmentoDe(a.getBatchId())));
        return asigs;
    }

    private LocalDateTime ahoraDelJob(String jobId) {
        EstadoJob job = jobs.get(jobId);
        if (job == null) return null;
        BloqueSimulacion ultimo = job.ultimoBloque();
        if (ultimo == null || ultimo.getHoraFin() == null) return null;
        try {
            return LocalDateTime.parse(ultimo.getHoraFin());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    List<AsignacionMaleta> buildAsignaciones(List<LoteEnvio> batches) {
        return batches.stream().map(b -> {
            List<Arista> rutaCompleta = b.getRutaCompleta();
            List<Long> depsCompletas = b.getSalidasCompletas();
            boolean enrutada = b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty();
            boolean tieneTramos = rutaCompleta != null && !rutaCompleta.isEmpty()
                    && depsCompletas != null && depsCompletas.size() == rutaCompleta.size();
            AsignacionMaleta asig = new AsignacionMaleta();
            asig.setBatchId(b.getId());
            asig.setOrigen(b.getCodigoOrigen());
            asig.setDestino(b.getCodigoDestino());
            asig.setCantidad(b.getCantidad());
            if (b.esFragmento()) {
                asig.setIdEnvioPadre(b.getIdPadre());
                asig.setFragmento(b.getFragmento());
                asig.setTotalFragmentos(b.getTotalFragmentos());
            }
            asig.setEnrutada(enrutada);
            asig.setCumpleSLA(b.isCumpleSLA());
            asig.setRutaVuelos(tieneTramos
                    ? rutaCompleta.stream().map(e -> e.id).collect(Collectors.toList())
                    : Collections.emptyList());

            LocalDateTime ready = b.getTiempoListo();
            if (ready != null) {
                long readyUtcMin = AcumuladorAuditoria.aMinutoEpoch(ready);
                asig.setRegistroUtc(epochMinToIso(readyUtcMin));
                asig.setRegistroLocal(epochMinToIso(readyUtcMin + offsetHoras(b.getCodigoOrigen()) * 60L));
            }

            List<TramoRuta> tramos = Collections.emptyList();
            if (tieneTramos) {
                var route = rutaCompleta;
                var deps = depsCompletas;
                tramos = new ArrayList<>();
                for (int ti = 0; ti < route.size(); ti++) {
                    var edge = route.get(ti);
                    long depMin = deps.get(ti);
                    long arrMin = depMin + edge.duracionMinutos;
                    String origen  = edge.origen != null ? edge.origen.codigo : "";
                    String destino = edge.destino != null ? edge.destino.codigo : "";
                    TramoRuta tr = new TramoRuta();
                    tr.setVueloId(edge.id);
                    tr.setOrigen(origen);
                    tr.setDestino(destino);
                    tr.setSalidaUtc(epochMinToIso(depMin));
                    tr.setLlegadaUtc(epochMinToIso(arrMin));
                    tr.setSalidaLocal(epochMinToIso(depMin + offsetHoras(origen) * 60L));
                    tr.setLlegadaLocal(epochMinToIso(arrMin + offsetHoras(destino) * 60L));
                    tr.setDuracionMin(edge.duracionMinutos);
                    tramos.add(tr);
                }
            }
            asig.setTramos(tramos);
            return asig;
        }).collect(Collectors.toList());
    }

    List<AsignacionMaleta> construirEstadoInicial(Collection<LoteEnvio> batchesWarmup) {
        if (batchesWarmup == null || batchesWarmup.isEmpty()) return List.of();

        long relojMin = Long.MIN_VALUE;
        for (LoteEnvio b : batchesWarmup) {
            long readyMin = AcumuladorAuditoria.aMinutoEpoch(b.getTiempoListo());
            if (readyMin > relojMin) relojMin = readyMin;
        }

        List<LoteEnvio> activos = new ArrayList<>();
        for (LoteEnvio b : batchesWarmup) {
            boolean enrutada = b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty();
            if (enrutada && AcumuladorAuditoria.ultimoArriboMin(b) > relojMin) activos.add(b);
        }
        return buildAsignaciones(activos);
    }

    List<CargaVuelo> buildCargasVuelos(Map<Long, Integer> blockFlight, Grafo graph,
                                       OperadorReparacionVoraz enrutador) {
        if (blockFlight == null || blockFlight.isEmpty() || graph == null) return List.of();

        Map<Integer, Arista> edgesByIdx = new HashMap<>();
        for (Arista edge : graph.aristas) edgesByIdx.put(edge.indice, edge);

        List<CargaVuelo> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : blockFlight.entrySet()) {
            int carga = enrutador != null
                    ? enrutador.ocupacionGlobalVuelo(entry.getKey())
                    : entry.getValue();
            if (carga <= 0) continue;
            Arista edge = edgesByIdx.get(resourceIdx(entry.getKey()));
            if (edge == null) continue;

            LocalDateTime salida = LocalDate.ofEpochDay(epochDay(entry.getKey()))
                    .atStartOfDay()
                    .plusMinutes(edge.minutoDelDiaSalida);
            LocalDateTime llegada = salida.plusMinutes(edge.duracionMinutos);

            CargaVuelo dto = new CargaVuelo();
            dto.setVueloId(edge.id);
            dto.setOrigen(edge.origen != null ? edge.origen.codigo : "");
            dto.setDestino(edge.destino != null ? edge.destino.codigo : "");
            dto.setFechaSalida(salida.toString());
            dto.setFechaLlegada(llegada.toString());
            dto.setCapacidadMaxima(edge.capacidad);
            dto.setCargaAsignada(carga);
            FormatoSimulacion.completarCargaVuelo(dto);
            out.add(dto);
        }
        out.sort(Comparator.comparing(CargaVuelo::getFechaSalida)
                .thenComparing(CargaVuelo::getVueloId));
        return out;
    }

    List<OcupacionAlmacen> buildOcupacionAlmacenes(Map<Long, Integer> blockAirport,
                                                   Grafo graph,
                                                   OperadorReparacionVoraz enrutador) {
        if (blockAirport == null || blockAirport.isEmpty() || graph == null) return List.of();

        Map<Integer, Nodo> nodesByIdx = new HashMap<>();
        for (Nodo node : graph.nodos.values()) nodesByIdx.put(node.indice, node);

        Map<Long, Integer> picoPorAeroDia = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : blockAirport.entrySet()) {
            int ocupacion = enrutador != null
                    ? enrutador.ocupacionGlobalAlmacen(entry.getKey())
                    : entry.getValue();
            if (ocupacion <= 0) continue;
            int nodeIdx = CodificadorClaveVuelo.indiceNodoDeSlot(entry.getKey());
            long slot = CodificadorClaveVuelo.slotDe(entry.getKey());
            long epochDia = (slot * OperadorReparacionVoraz.SLOT_ALMACEN_MIN) / CodificadorClaveVuelo.MIN_DIA;
            long claveAeroDia = (((long) nodeIdx) << CodificadorClaveVuelo.BITS_DIA) | (epochDia & CodificadorClaveVuelo.MASCARA_DIA);
            picoPorAeroDia.merge(claveAeroDia, ocupacion, Integer::max);
        }

        List<OcupacionAlmacen> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : picoPorAeroDia.entrySet()) {
            Nodo node = nodesByIdx.get(resourceIdx(entry.getKey()));
            if (node == null) continue;

            OcupacionAlmacen dto = new OcupacionAlmacen();
            dto.setAeropuerto(node.codigo);
            dto.setFecha(LocalDate.ofEpochDay(epochDay(entry.getKey())).toString());
            dto.setCapacidadMaxima(node.capacidad);
            dto.setOcupacionAsignada(entry.getValue());
            FormatoSimulacion.completarOcupacionAlmacen(dto);
            out.add(dto);
        }
        out.sort(Comparator.comparing(OcupacionAlmacen::getFecha)
                .thenComparing(OcupacionAlmacen::getAeropuerto));
        return out;
    }

    List<OcupacionAlmacenSlot> buildSerieAlmacenes(Map<Long, Integer> blockAirport,
                                                   Grafo graph,
                                                   OperadorReparacionVoraz enrutador) {
        if (blockAirport == null || blockAirport.isEmpty() || graph == null) return List.of();

        Map<Integer, Nodo> nodesByIdx = new HashMap<>();
        for (Nodo node : graph.nodos.values()) nodesByIdx.put(node.indice, node);

        List<OcupacionAlmacenSlot> out = new ArrayList<>(blockAirport.size());
        for (Map.Entry<Long, Integer> entry : blockAirport.entrySet()) {
            int ocupacion = enrutador != null
                    ? enrutador.ocupacionGlobalAlmacen(entry.getKey())
                    : entry.getValue();
            if (ocupacion <= 0) continue;
            Nodo node = nodesByIdx.get(CodificadorClaveVuelo.indiceNodoDeSlot(entry.getKey()));
            if (node == null) continue;

            long slot = CodificadorClaveVuelo.slotDe(entry.getKey());
            long epochMin = slot * OperadorReparacionVoraz.SLOT_ALMACEN_MIN;

            OcupacionAlmacenSlot dto = new OcupacionAlmacenSlot();
            dto.setAeropuerto(node.codigo);
            dto.setHora(epochMinToIso(epochMin));
            dto.setCapacidadMaxima(node.capacidad);
            dto.setOcupacion(ocupacion);
            double porcentaje = FormatoSimulacion.porcentaje(ocupacion, node.capacidad);
            dto.setPorcentajeOcupacion(porcentaje);
            dto.setSemaforo(FormatoSimulacion.semaforoPorPorcentaje(porcentaje));
            out.add(dto);
        }
        out.sort(Comparator.comparing(OcupacionAlmacenSlot::getHora)
                .thenComparing(OcupacionAlmacenSlot::getAeropuerto));
        return out;
    }

    static AlertaAlmacen construirAlertaAlmacen(
            List<OcupacionAlmacen> ocupaciones, int bloqueIdx) {
        AlertaAlmacen alerta = new AlertaAlmacen();
        alerta.setBloqueIdx(bloqueIdx);

        OcupacionAlmacen peor = null;
        if (ocupaciones != null) {
            for (OcupacionAlmacen o : ocupaciones) {
                if (peor == null || o.getPorcentajeOcupacion() > peor.getPorcentajeOcupacion()) {
                    peor = o;
                }
            }
        }
        if (peor == null) {
            alerta.setNivel("VERDE");
            return alerta;
        }
        alerta.setNivel(peor.getSemaforo());
        alerta.setAlmacenCritico(peor.getAeropuerto());
        alerta.setCapacidadMaxima(peor.getCapacidadMaxima());
        alerta.setOcupacion(peor.getOcupacionAsignada());
        alerta.setPorcentajeOcupacion(peor.getPorcentajeOcupacion());
        return alerta;
    }

    AlertaColapso construirAlertaColapso(PreColapso pre, int bloque) {
        var cfg = props.getAlertaColapso();
        String nivelAlmacen = AlertaColapso.VERDE;
        if (pre.utilAlmacenMax() >= cfg.getAlmacenRojo()) nivelAlmacen = AlertaColapso.ROJO;
        else if (pre.utilAlmacenMax() >= cfg.getAlmacenAmbar()) nivelAlmacen = AlertaColapso.AMBAR;
        String nivelBacklog = AlertaColapso.VERDE;
        if (pre.envioUrgente() != null) {
            if (pre.holguraSlaMin() <= cfg.getSlaRestanteRojo()) nivelBacklog = AlertaColapso.ROJO;
            else if (pre.holguraSlaMin() <= cfg.getSlaRestanteAmbar()) nivelBacklog = AlertaColapso.AMBAR;
        }
        String nivel = nivelMax(nivelAlmacen, nivelBacklog);

        StringBuilder msg = new StringBuilder();
        if (!AlertaColapso.VERDE.equals(nivelAlmacen)) {
            msg.append(String.format("almacén %s al %.0f%% de capacidad",
                    pre.almacenCritico(), pre.utilAlmacenMax() * 100));
        }
        if (!AlertaColapso.VERDE.equals(nivelBacklog)) {
            if (msg.length() > 0) msg.append(" | ");
            msg.append(String.format("envío %s al %.0f%% de su SLA en backlog",
                    pre.envioUrgente(), Math.max(0, pre.holguraSlaMin()) * 100));
        }
        if (msg.length() == 0) msg.append("Sin riesgo de colapso");

        boolean almacenActivo = !AlertaColapso.VERDE.equals(nivelAlmacen);
        boolean backlogActivo = !AlertaColapso.VERDE.equals(nivelBacklog);
        String causaDominante = almacenActivo && backlogActivo ? "ambos"
                : almacenActivo ? "almacen"
                : backlogActivo ? "sla"
                : null;

        return new AlertaColapso(
                nivel, msg.toString(), bloque,
                pre.utilAlmacenMax(), pre.almacenCritico(), pre.holguraSlaMin(), pre.envioUrgente(),
                causaDominante);
    }

    private static String nivelMax(String a, String b) {
        int ra = rango(a), rb = rango(b);
        return ra >= rb ? a : b;
    }

    private static int rango(String nivel) {
        if (AlertaColapso.ROJO.equals(nivel)) return 2;
        if (AlertaColapso.AMBAR.equals(nivel)) return 1;
        return 0;
    }

    SimulacionResponse construirRespuestaFront(int enrutadas, long tiempoMs,
                                               List<Vuelo> vuelosReales,
                                               int totalBloques,
                                               LocalDate simulationDate) {
        SimulacionResponse res = new SimulacionResponse();
        Metricas m = new Metricas();
        m.setEnrutadas(enrutadas);
        m.setTiempoEjecucionMs(tiempoMs);
        res.setMetricas(m);
        res.setTotalBloques(totalBloques);

        long dayShift = simulationDate != null
                ? ChronoUnit.DAYS.between(AnalizadorVuelos.FLIGHT_BASE_DATE, simulationDate) : 0L;

        List<VueloBackend> vuelosFront = new ArrayList<>();
        Map<String, AeropuertoDTO> infoAero = new HashMap<>();
        for (Vuelo v : vuelosReales) {
            VueloBackend vb = new VueloBackend();
            vb.setId(FormatoSimulacion.vueloFrontId(v));
            vb.setOrigen(v.getOrigen());
            vb.setDestino(v.getDestino());
            vb.setFechaSalida(v.getFechaHoraSalida().plusDays(dayShift).toString());
            vb.setFechaLlegada(v.getFechaHoraLlegada().plusDays(dayShift).toString());
            vb.setCapacidadMaxima(v.getCapacidad() != null ? v.getCapacidad() : 0);
            vb.setCargaAsignada(0);
            vuelosFront.add(vb);
            agregarInfoAeropuerto(infoAero, v.getOrigen(), v.getAeropuertoOrigen());
            agregarInfoAeropuerto(infoAero, v.getDestino(), v.getAeropuertoDestino());
        }
        res.setVuelosPlaneados(vuelosFront);
        res.setAeropuertosInfo(infoAero);
        return res;
    }

    void llenarMetricas(Metricas m,
                        int envios, int enrutadas, int sinRuta,
                        int cumpleSLA, int tardadas, long maletas,
                        int vuelosCancelados,
                        boolean collapso, int bloqueCollapso,
                        String motivoColapso, String detalleColapso,
                        LocalDateTime instanteColapso) {
        m.setProcesadas(envios);
        m.setEnrutadas(enrutadas);
        m.setSinRuta(sinRuta);
        m.setCumpleSLA(cumpleSLA);
        m.setTardadas(tardadas);
        m.setMaletasIndividuales(maletas);
        m.setVuelosCancelados(vuelosCancelados);
        m.setCollapsoDetectado(collapso);
        m.setBloqueColapso(bloqueCollapso);
        m.setMotivoColapso(motivoColapso);
        m.setDetalleColapso(detalleColapso);
        m.setInstanteColapsoUtc(instanteColapso != null ? instanteColapso.toString() : null);
    }

    void llenarMetricasTa(Metricas m, EstadisticasTa stats, long saMs) {
        m.setTaMinMs(stats.min());
        m.setTaMaxMs(stats.max());
        m.setTaPromedioMs(stats.promedio());
        m.setTiempoTotalAlgMs(stats.suma());
        m.setAdvertenciaCalibracion(stats.max() > saMs * 0.9);
    }

    void llenarMetricasBacklog(Metricas m, GestorBacklog backlog) {
        m.setBacklogActual(backlog.tamaño());
        m.setBacklogPico(backlog.picoHistorico());
        m.setSinRutaDefinitivo(backlog.sinRutaDefinitivo());
    }

    Metricas metricasSnapshotDe(TotalesUnicos t, long taPromedioMs) {
        Metricas m = new Metricas();
        m.setProcesadas(t.envios());
        m.setEnrutadas(t.enrutadas());
        m.setSinRuta(t.sinRuta());
        m.setCumpleSLA(t.cumpleSLA());
        m.setTardadas(t.tardadas());
        m.setMaletasIndividuales(t.maletas());
        m.setTaPromedioMs(taPromedioMs);
        return m;
    }

    private void agregarInfoAeropuerto(Map<String, AeropuertoDTO> map, String cod, Aeropuerto a) {
        if (!map.containsKey(cod)) {
            AeropuertoDTO dto = new AeropuertoDTO();
            dto.setCodigo(cod);
            dto.setLatitud(a.getLatitud());
            dto.setLongitud(a.getLongitud());
            dto.setCapacidadAlmacen(a.getCapacidad());
            dto.setGmt(a.getOffsetHorario() != null ? a.getOffsetHorario().doubleValue() : 0.0);
            map.put(cod, dto);
        }
    }

    private int offsetHoras(String codigo) {
        return cacheOffsets.offsetHoras(codigo);
    }

    private static int resourceIdx(long key) {
        return (int) (key >> CodificadorClaveVuelo.BITS_DIA);
    }

    private static long epochDay(long key) {
        return key & CodificadorClaveVuelo.MASCARA_DIA;
    }

    private static String epochMinToIso(long epochMin) {
        long epochDay = Math.floorDiv(epochMin, 1440L);
        int minuteOfDay = (int) Math.floorMod(epochMin, 1440L);
        return LocalDateTime.of(
                LocalDate.ofEpochDay(epochDay),
                LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        ).toString();
    }
}
