package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Slf4j
@Component
public class ColoniaACO {

    private static final double IMPULSO_FEROMONA_BASE = 2.0;
    private static final double RESERVA_BASE = 0.15;
    private static final double UMBRAL_CONGESTION_DEFER = 2.0;
    private static final long   MARGEN_DIFERIR_MIN = 1440L;
    private static final int    CANDIDATOS_RUTA_GRUPO = 5;
    private static final boolean ENABLE_J3_DEFER = false;

    private final PlanificadorProperties props;
    private int diagSeq = 0;

    public ColoniaACO(PlanificadorProperties props) {
        this.props = props;
    }

    public int procesar(Grafo graph,
                         OperadorReparacionVoraz enrutador,
                         List<LoteEnvio> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport) {
        return procesar(graph, enrutador, batches, blockFlight, blockAirport, null, 0L);
    }

    public int procesar(Grafo graph,
                         OperadorReparacionVoraz enrutador,
                         List<LoteEnvio> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport,
                         Random rng) {
        return procesar(graph, enrutador, batches, blockFlight, blockAirport, rng, 0L);
    }

    public int procesar(Grafo graph,
                         OperadorReparacionVoraz enrutador,
                         List<LoteEnvio> batches,
                         Map<Long, Integer> blockFlight,
                         Map<Long, Integer> blockAirport,
                         Random rng,
                         long tiempoLimiteMs) {
        if (batches == null || batches.isEmpty()) return 0;

        Random random = rng != null ? rng : new Random();
        ConfiguracionACO cfg = configurar(batches.size());
        List<LoteEnvio> base = ordenarPorUrgencia(batches);

        Map<LoteEnvio, String> batchKeys = new IdentityHashMap<>(base.size() * 2);
        for (LoteEnvio b : base) batchKeys.put(b, RastroFeromonas.claveLote(b));

        long inicio = System.nanoTime();
        long deadline = tiempoLimiteMs > 0
                ? inicio + tiempoLimiteMs * 1_000_000L
                : Long.MAX_VALUE;

        EstadisticasBusqueda stats = new EstadisticasBusqueda();
        RastroFeromonas feromonas = new RastroFeromonas(cfg);
        GeneradorRutas generador = new GeneradorRutas(enrutador, stats);
        Heuristica heuristica = new Heuristica();
        RuletaSeleccion ruleta = new RuletaSeleccion(feromonas, heuristica, random);
        ConstructorHormiga hormiga = new ConstructorHormiga(
                enrutador, generador, heuristica, ruleta, feromonas, random);

        SolucionBloque mejor = null;
        int sinMejora = 0;
        int solucionesEvaluadas = 0;

        Set<LoteEnvio> diferidos = Collections.newSetFromMap(new IdentityHashMap<>());
        SolucionBloque baseDeterministica = construirSolucionBase(
                enrutador, generador, heuristica, base, blockFlight, blockAirport, deadline, batchKeys, diferidos);
        if (baseDeterministica != null) {
            mejor = baseDeterministica;
            solucionesEvaluadas++;
            if (baseDeterministica.enrutados == base.size()) stats.solucionesCompletas++;
            feromonas.depositar(baseDeterministica, cfg.q * IMPULSO_FEROMONA_BASE);
        }

        List<LoteEnvio> baseAnts = base;
        if (!diferidos.isEmpty()) {
            baseAnts = new ArrayList<>(base.size() - diferidos.size());
            for (LoteEnvio b : base) if (!diferidos.contains(b)) baseAnts.add(b);
        }

        for (int iter = 0; iter < cfg.iteraciones && System.nanoTime() < deadline; iter++) {
            boolean huboMejora = false;

            for (int ant = 0; ant < cfg.cantidadHormigas && System.nanoTime() < deadline; ant++) {
                SolucionBloque candidata = hormiga.construir(
                        baseAnts, blockFlight, blockAirport, batchKeys, deadline);
                solucionesEvaluadas++;
                if (candidata.enrutados == base.size()) stats.solucionesCompletas++;
                if (mejorQue(candidata, mejor)) {
                    mejor = candidata;
                    huboMejora = true;
                }
            }

            feromonas.evaporar(cfg.evaporacion);
            if (mejor != null) feromonas.depositar(mejor, cfg.q);

            sinMejora = huboMejora ? 0 : sinMejora + 1;
            if (cfg.maxSinMejora > 0 && sinMejora >= cfg.maxSinMejora) break;
        }

        for (LoteEnvio b : batches) {
            b.limpiarRuta();
            b.setCumpleSLA(false);
        }

        int enrutados = 0;
        int onTime = 0;
        if (mejor != null) {
            for (Asignacion asignacion : mejor.asignaciones) {
                if (!asignacion.ruta.isCumpleSLA()) continue;
                enrutador.aplicarCandidatoRuta(asignacion.lote, asignacion.ruta);
                enrutador.aplicarCandidatoBloque(asignacion.lote, asignacion.ruta, blockFlight, blockAirport);
                enrutados++;
                onTime++;
            }
        }

        long elapsedMs = (System.nanoTime() - inicio) / 1_000_000L;
        int sinRuta = batches.size() - enrutados;
        if (log.isInfoEnabled() && ++diagSeq % 50 == 0) {
            log.info("ACO padre bloque batches={} enrutados={} onTime={} sinRuta={} soluciones={} completas={} cacheHits={} cacheRejects={} dijkstraCalls={} t={}ms",
                    batches.size(), enrutados, onTime, sinRuta, solucionesEvaluadas, stats.solucionesCompletas,
                    stats.cacheHits, stats.cacheRejects, stats.dijkstraCalls, elapsedMs);
        } else if (log.isDebugEnabled()) {
            log.debug("ACO padre bloque batches={} enrutados={} onTime={} sinRuta={} soluciones={} completas={} cacheHits={} cacheRejects={} dijkstraCalls={} t={}ms",
                    batches.size(), enrutados, onTime, sinRuta, solucionesEvaluadas, stats.solucionesCompletas,
                    stats.cacheHits, stats.cacheRejects, stats.dijkstraCalls, elapsedMs);
        }
        return enrutados;
    }

    List<LoteEnvio> ordenarPorUrgencia(List<LoteEnvio> batches) {
        List<LoteEnvio> copia = new ArrayList<>(batches);
        copia.sort(Comparator
                .comparingLong(ColoniaACO::fechaLimiteEpochMin)
                .thenComparing(Comparator.comparingInt(LoteEnvio::getCantidad).reversed())
                .thenComparing(LoteEnvio::getCodigoOrigen)
                .thenComparing(LoteEnvio::getCodigoDestino));
        return copia;
    }

    private static long fechaLimiteEpochMin(LoteEnvio batch) {
        return OperadorReparacionVoraz.aMinutoEpochPublico(batch.getTiempoListo())
                + (long) batch.getHorasLimiteSla() * 60L;
    }

    private ConfiguracionACO configurar(int batchCount) {
        ConfiguracionACO cfg = new ConfiguracionACO();
        if (batchCount > 100) {
            cfg.cantidadHormigas = 8;
            cfg.iteraciones = 54;
        } else if (batchCount > 30) {
            cfg.cantidadHormigas = 10;
            cfg.iteraciones = 84;
        } else {
            cfg.cantidadHormigas = 14;
            cfg.iteraciones = 108;
        }
        cfg.maxSinMejora = 8;
        cfg.alpha = 1.0;
        cfg.beta = 2.0;
        cfg.evaporacion = 0.30;
        cfg.q = 100.0;
        cfg.feromonaInicial = 1.0;
        return cfg;
    }

    private SolucionBloque construirSolucionBase(OperadorReparacionVoraz enrutador,
                                                 GeneradorRutas generador,
                                                 Heuristica heuristica,
                                                 List<LoteEnvio> base,
                                                 Map<Long, Integer> blockFlight,
                                                 Map<Long, Integer> blockAirport,
                                                 long deadline,
                                                 Map<LoteEnvio, String> batchKeys,
                                                 Set<LoteEnvio> diferidos) {
        Map<Long, Integer> simFlight = new HashMap<>(blockFlight);
        Map<Long, Integer> simAirport = new HashMap<>(blockAirport);
        List<Asignacion> asignaciones = new ArrayList<>();

        Map<String, List<LoteEnvio>> grupos = new LinkedHashMap<>();
        for (LoteEnvio batch : base) {
            grupos.computeIfAbsent(claveGrupo(batch), k -> new ArrayList<>()).add(batch);
        }

        for (List<LoteEnvio> grupo : grupos.values()) {
            if (System.nanoTime() >= deadline) break;

            LoteEnvio rep = grupo.get(0);
            for (LoteEnvio b : grupo) {
                if (b.getCantidad() > rep.getCantidad()) rep = b;
            }
            List<RutaCandidata> rutasGrupo = generador.obtenerRutas(
                    rep, simFlight, simAirport, CANDIDATOS_RUTA_GRUPO);

            for (LoteEnvio batch : grupo) {
                if (System.nanoTime() >= deadline) break;

                RutaCandidata elegida = seleccionarRuta(enrutador, heuristica, batch, rutasGrupo, simFlight, simAirport);
                if (elegida == null) {
                    List<RutaCandidata> propias = generador.obtenerRutas(
                            batch, simFlight, simAirport, CANDIDATOS_RUTA_GRUPO);
                    elegida = seleccionarRuta(enrutador, heuristica, batch, propias, simFlight, simAirport);
                }
                if (elegida == null) continue;

                if (ENABLE_J3_DEFER
                        && elegida.getHolguraMin() > MARGEN_DIFERIR_MIN
                        && elegida.getCostoEscasez() > UMBRAL_CONGESTION_DEFER) {
                    diferidos.add(batch);
                    continue;
                }

                String bKey = batchKeys.get(batch);
                enrutador.aplicarCandidatoBloque(batch, elegida, simFlight, simAirport);
                asignaciones.add(new Asignacion(batch, elegida, RastroFeromonas.claveFeromona(bKey, elegida), bKey));
            }
        }

        return new SolucionBloque(asignaciones, base.size());
    }

    private RutaCandidata seleccionarRuta(OperadorReparacionVoraz enrutador,
                                           Heuristica heuristica,
                                           LoteEnvio batch,
                                           List<RutaCandidata> candidatos,
                                           Map<Long, Integer> simFlight,
                                           Map<Long, Integer> simAirport) {
        double reservaAlmacen = props.getStorageAware().getReservaAlmacenBase();
        RutaCandidata best = mejorPorCosto(enrutador, heuristica, batch, candidatos, simFlight, simAirport,
                RESERVA_BASE, reservaAlmacen);
        if (best == null && (RESERVA_BASE > 0.0 || reservaAlmacen > 0.0)) {
            best = mejorPorCosto(enrutador, heuristica, batch, candidatos, simFlight, simAirport, 0.0, 0.0);
        }
        return best;
    }

    private RutaCandidata mejorPorCosto(OperadorReparacionVoraz enrutador,
                                         Heuristica heuristica,
                                         LoteEnvio batch,
                                         List<RutaCandidata> candidatos,
                                         Map<Long, Integer> simFlight,
                                         Map<Long, Integer> simAirport,
                                         double reservaBase,
                                         double reservaAlmacenBase) {
        RutaCandidata best = null;
        double bestCost = Double.MAX_VALUE;
        for (RutaCandidata r : candidatos) {
            if (!r.isCumpleSLA()) continue;                                  // F1
            if (!enrutador.rutaSirveParaLote(r, batch, simFlight, simAirport,
                    reservaBase, reservaAlmacenBase)) continue;
            double c = heuristica.costoSeleccion(batch, r);
            if (c < bestCost) { bestCost = c; best = r; }
        }
        return best;
    }

    private String claveGrupo(LoteEnvio batch) {
        long readyBucket = batch.getTiempoListo() == null
                ? 0L
                : OperadorReparacionVoraz.aMinutoEpochPublico(batch.getTiempoListo()) / GeneradorRutas.CACHE_BUCKET_MIN;
        return batch.getCodigoOrigen() + '|' + batch.getCodigoDestino()
                + '|' + readyBucket + '|' + batch.getHorasLimiteSla();
    }

    private boolean mejorQue(SolucionBloque candidata, SolucionBloque actual) {
        if (candidata == null) return false;
        if (actual == null) return true;
        if (candidata.cumpleSla != actual.cumpleSla) {
            return candidata.cumpleSla > actual.cumpleSla;
        }
        if (candidata.enrutados != actual.enrutados) {
            return candidata.enrutados > actual.enrutados;
        }
        if (candidata.tardados != actual.tardados) {
            return candidata.tardados < actual.tardados;
        }
        return candidata.costo < actual.costo;
    }
}
