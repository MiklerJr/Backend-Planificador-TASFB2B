package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.ComparativaRequest;
import com.tasfb2b.planificador.dto.ComparativaResultado;
import com.tasfb2b.planificador.dto.ComparativaRow;
import com.tasfb2b.planificador.dto.EjecucionParams;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orquesta comparativas pareadas ALNS vs ACO para análisis estadístico
 * (test de Wilcoxon de rangos con signo).
 *
 * <p>Cada repetición {@code r} ejecuta dos corridas con {@code seed = seedBase + r}:
 * una con motor ALNS y otra con motor ACO. Como los seeds son idénticos, ambos
 * motores ven exactamente el mismo problema (mismas cancelaciones, mismas
 * maletas, mismo plan de bloques). Las métricas resultantes son <b>pares</b>
 * apropiados para Wilcoxon.
 *
 * <p>Ejecución estrictamente secuencial (single-thread executor) para que las
 * mediciones de Ta/tiempo real sean comparables sin contención de CPU.
 *
 * <p>Importante: no lanzar simulaciones manuales mientras corre una comparativa
 * — comparten el mismo {@link PlanificadorService} y los Ta dejarían de ser
 * representativos.
 */
@Slf4j
@Service
public class ComparativaService {

    private final PlanificadorService planificador;
    private final ConcurrentHashMap<String, ComparativaResultado> results = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "comparativa-runner");
                t.setDaemon(true);
                return t;
            });

    public ComparativaService(PlanificadorService planificador) {
        this.planificador = planificador;
    }

    public ComparativaResultado iniciar(ComparativaRequest req) {
        if (req == null) req = new ComparativaRequest();
        int reps = (req.getRepeticiones() != null && req.getRepeticiones() > 0) ? req.getRepeticiones() : 30;
        int k    = (req.getK() != null && req.getK() > 0) ? req.getK() : 14;
        double cp = req.getCancelProb() != null ? Math.max(0.0, Math.min(1.0, req.getCancelProb())) : 0.0;
        long seedBase = req.getSeedBase() != null ? req.getSeedBase() : 42L;
        boolean e3 = req.isEjecutarColapso();
        double umbral = req.getUmbralColapso() != null ? req.getUmbralColapso() : 0.20;
        LocalDateTime fechaInicio = req.getFechaInicio();
        Integer sa = req.getSa();
        Integer ta = req.getTa();
        Integer dias = req.getDias();
        String motor = normalizarMotor(req.getAlgoritmo() != null ? req.getAlgoritmo() : req.getMotor());

        // Cada repetición = 2 corridas (ALNS + ACO). Si además ejecutamos E3, son 4 por rep.
        int filasTotales = reps * (e3 ? 2 : 1);

        ComparativaResultado res = new ComparativaResultado();
        res.setJobId(UUID.randomUUID().toString());
        res.setEstado("ejecutando");
        res.setInicio(LocalDateTime.now());
        res.setFilasTotales(filasTotales);
        res.setFilasCompletadas(0);
        results.put(res.getJobId(), res);

        log.info("Comparativa {} iniciada: {} reps × {} escenarios, K={}, cancelProb={}, seedBase={}, fechaInicio={}, sa={}, ta={}, dias={}",
                res.getJobId(), reps, e3 ? 2 : 1, k, cp, seedBase, fechaInicio, sa, ta, dias);

        final int repsF = reps;
        final int kF = k;
        final double cpF = cp;
        final long seedBaseF = seedBase;
        final boolean e3F = e3;
        final double umbralF = umbral;
        final LocalDateTime fechaInicioF = fechaInicio;
        final Integer saF = sa;
        final Integer taF = ta;
        final Integer diasF = dias;
        final String motorF = motor;
        executor.submit(() -> ejecutar(res, repsF, kF, cpF, seedBaseF, e3F, umbralF, fechaInicioF, saF, taF, diasF, motorF));
        return res;
    }

    public ComparativaResultado get(String jobId) {
        return results.get(jobId);
    }

    // ── Núcleo ─────────────────────────────────────────────────────────────

    private void ejecutar(ComparativaResultado res, int reps, int k, double cancelProb,
                           long seedBase, boolean ejecutarColapso, double umbralColapso,
                           LocalDateTime fechaInicio, Integer sa, Integer ta, Integer dias,
                           String motorSeleccionado) {
        try {
            boolean ejecutarAlns = "ambos".equals(motorSeleccionado) || "alns".equals(motorSeleccionado);
            boolean ejecutarAco = "ambos".equals(motorSeleccionado) || "aco".equals(motorSeleccionado);

            for (int r = 0; r < reps; r++) {
                long seed = seedBase;
                String suf = (fechaInicio != null ? " desde=" + fechaInicio : "")
                        + (sa != null ? " sa=" + sa : "")
                        + (ta != null ? " ta=" + ta : "")
                        + (dias != null ? " dias=" + dias : "");

                // Escenario 2 — período completo
                ComparativaRow row2 = new ComparativaRow();
                row2.setEscenario("2");
                row2.setRep(r);
                row2.setSeed(seed);
                row2.setK(k);
                row2.setCancelProb(cancelProb);

                if (ejecutarAlns) {
                res.setConfigActual(String.format("rep %d/%d — E2 ALNS K=%d cp=%.2f seed=%d%s", r + 1, reps, k, cancelProb, seed, suf));
                log.info("Comparativa {} → {}", res.getJobId(), res.getConfigActual());
                long t0 = System.currentTimeMillis();
                SimulacionResponse alns = planificador.ejecutarALNS(
                        construirParams(k, cancelProb, "alns", seed, fechaInicio, sa, ta, dias), null);
                long alnsMs = System.currentTimeMillis() - t0;
                rellenarAlns(row2, alns, alnsMs);
                }

                if (ejecutarAco) {
                res.setConfigActual(String.format("rep %d/%d — E2 ACO K=%d cp=%.2f seed=%d%s", r + 1, reps, k, cancelProb, seed, suf));
                log.info("Comparativa {} → {}", res.getJobId(), res.getConfigActual());
                long t0 = System.currentTimeMillis();
                SimulacionResponse aco = planificador.ejecutarALNS(
                        construirParams(k, cancelProb, "aco", seed, fechaInicio, sa, ta, dias), null);
                long acoMs = System.currentTimeMillis() - t0;
                rellenarAco(row2, aco, acoMs);
                }

                res.getFilas().add(row2);
                res.setFilasCompletadas(res.getFilasCompletadas() + 1);

                // Escenario 3 — hasta colapso (opcional)
                if (ejecutarColapso) {
                    ComparativaRow row3 = new ComparativaRow();
                    row3.setEscenario("3");
                    row3.setRep(r);
                    row3.setSeed(seed);
                    row3.setK(k);
                    row3.setCancelProb(cancelProb);

                    if (ejecutarAlns) {
                    res.setConfigActual(String.format("rep %d/%d — E3 ALNS K=%d cp=%.2f seed=%d", r + 1, reps, k, cancelProb, seed));
                    log.info("Comparativa {} → {}", res.getJobId(), res.getConfigActual());
                    long t0 = System.currentTimeMillis();
                    SimulacionResponse alns3 = planificador.ejecutarHastaColapso(k, cancelProb, umbralColapso, null, "alns", seed);
                    long alns3Ms = System.currentTimeMillis() - t0;
                    rellenarAlns(row3, alns3, alns3Ms);
                    }

                    if (ejecutarAco) {
                    res.setConfigActual(String.format("rep %d/%d — E3 ACO K=%d cp=%.2f seed=%d", r + 1, reps, k, cancelProb, seed));
                    log.info("Comparativa {} → {}", res.getJobId(), res.getConfigActual());
                    long t0 = System.currentTimeMillis();
                    SimulacionResponse aco3 = planificador.ejecutarHastaColapso(k, cancelProb, umbralColapso, null, "aco", seed);
                    long aco3Ms = System.currentTimeMillis() - t0;
                    rellenarAco(row3, aco3, aco3Ms);
                    }

                    res.getFilas().add(row3);
                    res.setFilasCompletadas(res.getFilasCompletadas() + 1);
                }
            }

            res.setEstado("completado");
            res.setFin(LocalDateTime.now());
            res.setConfigActual(null);
            log.info("Comparativa {} completada en {} filas", res.getJobId(), res.getFilas().size());

        } catch (Exception ex) {
            res.setEstado("error");
            res.setError(ex.getMessage());
            res.setFin(LocalDateTime.now());
            log.error("Comparativa {} falló: {}", res.getJobId(), ex.getMessage(), ex);
        }
    }

    private static String normalizarMotor(String motor) {
        if (motor == null || motor.isBlank()) return "ambos";
        String m = motor.trim().toLowerCase();
        if ("both".equals(m) || "todos".equals(m)) return "ambos";
        if ("ambos".equals(m) || "alns".equals(m) || "aco".equals(m)) return m;
        throw new IllegalArgumentException("Motor desconocido: " + motor + " (use 'ambos', 'alns' o 'aco')");
    }

    /** Construye un EjecucionParams para una corrida puntual de la comparativa. */
    private static EjecucionParams construirParams(int k, double cancelProb, String motor,
                                                    long seed, LocalDateTime fechaInicio,
                                                    Integer sa, Integer ta, Integer dias) {
        EjecucionParams p = new EjecucionParams();
        p.setK(k);
        p.setCancelProb(cancelProb);
        p.setMotor(motor);
        p.setSeed(seed);
        p.setFechaInicio(fechaInicio);
        p.setSaMin(sa);
        p.setTaSegundos(ta);
        p.setDias(dias);
        return p;
    }

    private static void rellenarAlns(ComparativaRow row, SimulacionResponse r, long tiempoRealMs) {
        SimulacionResponse.Metricas m = r.getMetricas();
        row.setAlnsEnvios(m.getProcesadas());
        row.setAlnsEnrutadas(m.getEnrutadas());
        row.setAlnsSinRuta(m.getSinRuta());
        row.setAlnsCumpleSLA(m.getCumpleSLA());
        row.setAlnsTardadas(m.getTardadas());
        row.setAlnsPctSLA(m.getProcesadas() > 0 ? (double) m.getCumpleSLA() / m.getProcesadas() : 0.0);
        row.setAlnsPctSinRuta(m.getProcesadas() > 0 ? (double) m.getSinRuta() / m.getProcesadas() : 0.0);
        row.setAlnsTaPromedioMs(m.getTaPromedioMs());
        row.setAlnsTaMaxMs(m.getTaMaxMs());
        row.setAlnsTiempoRealMs(tiempoRealMs);
        row.setAlnsBacklogPico(m.getBacklogPico());
        row.setAlnsSinRutaDefinitivo(m.getSinRutaDefinitivo());
        row.setAlnsCollapsoDetectado(m.isCollapsoDetectado());
        row.setAlnsBloqueColapso(m.getBloqueColapso());
        row.setAlnsCollapsoDetectado(m.isCollapsoDetectado());
        row.setAlnsBloqueColapso(m.getBloqueColapso());

        // ---> NUEVO: Cálculo de ms por paquete ALNS
        if (m.getEnrutadas() > 0) {
            row.setAlnsMsPorPaquete((double) tiempoRealMs / m.getEnrutadas());
        } else {
            row.setAlnsMsPorPaquete(0.0);
        }
    }

    private static void rellenarAco(ComparativaRow row, SimulacionResponse r, long tiempoRealMs) {
        SimulacionResponse.Metricas m = r.getMetricas();
        row.setAcoEnvios(m.getProcesadas());
        row.setAcoEnrutadas(m.getEnrutadas());
        row.setAcoSinRuta(m.getSinRuta());
        row.setAcoCumpleSLA(m.getCumpleSLA());
        row.setAcoTardadas(m.getTardadas());
        row.setAcoPctSLA(m.getProcesadas() > 0 ? (double) m.getCumpleSLA() / m.getProcesadas() : 0.0);
        row.setAcoPctSinRuta(m.getProcesadas() > 0 ? (double) m.getSinRuta() / m.getProcesadas() : 0.0);
        row.setAcoTaPromedioMs(m.getTaPromedioMs());
        row.setAcoTaMaxMs(m.getTaMaxMs());
        row.setAcoTiempoRealMs(tiempoRealMs);
        row.setAcoBacklogPico(m.getBacklogPico());
        row.setAcoSinRutaDefinitivo(m.getSinRutaDefinitivo());
        row.setAcoCollapsoDetectado(m.isCollapsoDetectado());
        row.setAcoBloqueColapso(m.getBloqueColapso());
        row.setAcoCollapsoDetectado(m.isCollapsoDetectado());
        row.setAcoBloqueColapso(m.getBloqueColapso());

        // ---> NUEVO: Cálculo de ms por paquete ACO
        if (m.getEnrutadas() > 0) {
            row.setAcoMsPorPaquete((double) tiempoRealMs / m.getEnrutadas());
        } else {
            row.setAcoMsPorPaquete(0.0);
        }
    }

    // ── Serialización a CSV ────────────────────────────────────────────────

    private static final String CSV_HEADER =
            "escenario,rep,seed,k,cancelProb," +
                    "alns_envios,alns_enrutadas,alns_sinRuta,alns_cumpleSLA,alns_tardadas,alns_pctSLA,alns_pctSinRuta," +
                    "alns_taPromedioMs,alns_taMaxMs,alns_tiempoRealMs,alns_msPorPaquete,alns_backlogPico,alns_sinRutaDefinitivo," + // <-- Se agregó alns_msPorPaquete
                    "alns_collapsoDetectado,alns_bloqueColapso," +
                    "aco_envios,aco_enrutadas,aco_sinRuta,aco_cumpleSLA,aco_tardadas,aco_pctSLA,aco_pctSinRuta," +
                    "aco_taPromedioMs,aco_taMaxMs,aco_tiempoRealMs,aco_msPorPaquete,aco_backlogPico,aco_sinRutaDefinitivo," + // <-- Se agregó aco_msPorPaquete
                    "aco_collapsoDetectado,aco_bloqueColapso\n";

    public String aCsv(String jobId) {
        ComparativaResultado res = results.get(jobId);
        if (res == null) return null;
        StringBuilder sb = new StringBuilder(CSV_HEADER);
        for (ComparativaRow r : res.getFilas()) {
            sb.append(r.getEscenario()).append(',')
              .append(r.getRep()).append(',')
              .append(r.getSeed()).append(',')
              .append(r.getK()).append(',')
              .append(r.getCancelProb()).append(',')
              .append(r.getAlnsEnvios()).append(',')
              .append(r.getAlnsEnrutadas()).append(',')
              .append(r.getAlnsSinRuta()).append(',')
              .append(r.getAlnsCumpleSLA()).append(',')
              .append(r.getAlnsTardadas()).append(',')
              .append(r.getAlnsPctSLA()).append(',')
              .append(r.getAlnsPctSinRuta()).append(',')
              .append(r.getAlnsTaPromedioMs()).append(',')
              .append(r.getAlnsTaMaxMs()).append(',')
              .append(r.getAlnsTiempoRealMs()).append(',')
                    .append(r.getAlnsMsPorPaquete()).append(',') // <--- NUEVO
              .append(r.getAlnsBacklogPico()).append(',')
              .append(r.getAlnsSinRutaDefinitivo()).append(',')
              .append(r.isAlnsCollapsoDetectado()).append(',')
              .append(r.getAlnsBloqueColapso()).append(',')
              .append(r.getAcoEnvios()).append(',')
              .append(r.getAcoEnrutadas()).append(',')
              .append(r.getAcoSinRuta()).append(',')
              .append(r.getAcoCumpleSLA()).append(',')
              .append(r.getAcoTardadas()).append(',')
              .append(r.getAcoPctSLA()).append(',')
              .append(r.getAcoPctSinRuta()).append(',')
              .append(r.getAcoTaPromedioMs()).append(',')
              .append(r.getAcoTaMaxMs()).append(',')
              .append(r.getAcoTiempoRealMs()).append(',')
                    .append(r.getAcoMsPorPaquete()).append(',') // <--- NUEVO
              .append(r.getAcoBacklogPico()).append(',')
              .append(r.getAcoSinRutaDefinitivo()).append(',')
              .append(r.isAcoCollapsoDetectado()).append(',')
              .append(r.getAcoBloqueColapso())
              .append('\n');
        }
        return sb.toString();
    }
}
