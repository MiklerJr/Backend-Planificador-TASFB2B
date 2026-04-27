package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.BenchmarkRequest;
import com.tasfb2b.planificador.dto.BenchmarkResult;
import com.tasfb2b.planificador.dto.BenchmarkRow;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orquesta benchmarks de calibración: ejecuta el planificador con múltiples
 * combinaciones de K × cancelProb × repeticiones y recolecta métricas para
 * recomendar valores óptimos de K para escenarios 2 y 3.
 *
 * <p>Single-thread executor — las corridas son secuenciales para que las
 * mediciones de Ta sean comparables (sin contención de CPU entre simulaciones).
 *
 * <p>Importante: no lanzar simulaciones manuales ({@code /escenario2/iniciar})
 * mientras corre un benchmark — comparten recursos y los Ta dejarán de ser
 * representativos.
 */
@Slf4j
@Service
public class BenchmarkService {

    private final PlanificadorService     planificador;
    private final PlanificadorProperties  props;
    private final ConcurrentHashMap<String, BenchmarkResult> results = new ConcurrentHashMap<>();
    private final ExecutorService         executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "benchmark-runner");
                t.setDaemon(true);
                return t;
            });

    public BenchmarkService(PlanificadorService planificador, PlanificadorProperties props) {
        this.planificador = planificador;
        this.props        = props;
    }

    /** Inicia un benchmark asíncrono. Devuelve el resultado parcial inicial con el jobId. */
    public BenchmarkResult iniciar(BenchmarkRequest req) {
        // Resolver parámetros: lo que venga en req, o defaults de application.yaml.
        List<Integer> kGrid = (req.getKGrid() != null && !req.getKGrid().isEmpty())
                ? req.getKGrid()
                : props.getBenchmark().getKGrid();
        List<Double> cancelProbGrid = (req.getCancelProbGrid() != null && !req.getCancelProbGrid().isEmpty())
                ? req.getCancelProbGrid()
                : props.getBenchmark().getCancelProbGrid();
        int reps = (req.getRepeticiones() != null && req.getRepeticiones() > 0)
                ? req.getRepeticiones()
                : props.getBenchmark().getRepeticiones();
        double umbralColapso = (req.getUmbralColapso() != null)
                ? req.getUmbralColapso()
                : props.getScenario().getUmbralColapso();

        int filasTotales = kGrid.size() * cancelProbGrid.size() * reps
                * (req.isEjecutarColapso() ? 2 : 1);

        BenchmarkResult result = new BenchmarkResult();
        result.setJobId(UUID.randomUUID().toString());
        result.setEstado("ejecutando");
        result.setInicio(LocalDateTime.now());
        result.setFilasTotales(filasTotales);
        result.setFilasCompletadas(0);

        results.put(result.getJobId(), result);
        log.info("Benchmark {} iniciado: {} corridas (K={}, cancelProb={}, reps={}, e3={})",
                result.getJobId(), filasTotales, kGrid, cancelProbGrid, reps, req.isEjecutarColapso());

        executor.submit(() -> ejecutar(result, kGrid, cancelProbGrid, reps, umbralColapso, req.isEjecutarColapso()));
        return result;
    }

    public BenchmarkResult get(String jobId) {
        return results.get(jobId);
    }

    // ── Núcleo del benchmark ─────────────────────────────────────────────────

    private void ejecutar(BenchmarkResult result, List<Integer> kGrid, List<Double> cancelProbGrid,
                          int reps, double umbralColapso, boolean ejecutarColapso) {
        try {
            int saMin = props.getScenario().getSaMinutos();

            // Escenario 2: período completo (sin colapso forzado)
            for (int k : kGrid) {
                for (double cp : cancelProbGrid) {
                    for (int r = 1; r <= reps; r++) {
                        result.setConfigActual(String.format("E2: K=%d, cancelProb=%.2f, rep=%d/%d", k, cp, r, reps));
                        log.info("Benchmark {} → {}", result.getJobId(), result.getConfigActual());
                        long t0 = System.currentTimeMillis();
                        SimulacionResponse res = planificador.ejecutarALNS(k, cp, null);
                        long tiempoRealMs = System.currentTimeMillis() - t0;

                        BenchmarkRow row = construirFila("2", k, saMin, cp, r, res, tiempoRealMs);
                        result.getFilas().add(row);
                        result.setFilasCompletadas(result.getFilasCompletadas() + 1);
                    }
                }
            }

            // Escenario 3: hasta colapso (opcional)
            if (ejecutarColapso) {
                for (int k : kGrid) {
                    for (double cp : cancelProbGrid) {
                        for (int r = 1; r <= reps; r++) {
                            result.setConfigActual(String.format("E3: K=%d, cancelProb=%.2f, rep=%d/%d", k, cp, r, reps));
                            log.info("Benchmark {} → {}", result.getJobId(), result.getConfigActual());
                            long t0 = System.currentTimeMillis();
                            SimulacionResponse res = planificador.ejecutarHastaColapso(k, cp, umbralColapso, null);
                            long tiempoRealMs = System.currentTimeMillis() - t0;

                            BenchmarkRow row = construirFila("3", k, saMin, cp, r, res, tiempoRealMs);
                            result.getFilas().add(row);
                            result.setFilasCompletadas(result.getFilasCompletadas() + 1);
                        }
                    }
                }
            }

            result.setRecomendacion(calcularRecomendacion(result, saMin));
            result.setEstado("completado");
            result.setFin(LocalDateTime.now());
            result.setConfigActual(null);
            log.info("Benchmark {} completado en {} corridas", result.getJobId(), result.getFilas().size());

        } catch (Exception ex) {
            result.setEstado("error");
            result.setError(ex.getMessage());
            result.setFin(LocalDateTime.now());
            log.error("Benchmark {} falló: {}", result.getJobId(), ex.getMessage(), ex);
        }
    }

    private BenchmarkRow construirFila(String escenario, int k, int saMin, double cp, int rep,
                                        SimulacionResponse res, long tiempoRealMs) {
        SimulacionResponse.Metricas m = res.getMetricas();
        BenchmarkRow row = new BenchmarkRow();
        row.setEscenario(escenario);
        row.setK(k);
        row.setSaMinutos(saMin);
        row.setScMinutos(k * saMin);
        row.setCancelProb(cp);
        row.setRepeticion(rep);

        row.setTotalBloques(res.getTotalBloques());
        row.setEnvios(m.getProcesadas());
        row.setEnrutadas(m.getEnrutadas());
        row.setSinRuta(m.getSinRuta());
        row.setCumpleSLA(m.getCumpleSLA());
        row.setTardadas(m.getTardadas());
        row.setMaletasIndividuales(m.getMaletasIndividuales());
        row.setPorcentajeCumpleSla(m.getEnrutadas() > 0 ? (double) m.getCumpleSLA() / m.getEnrutadas() : 0.0);
        row.setPorcentajeSinRuta  (m.getProcesadas() > 0 ? (double) m.getSinRuta()   / m.getProcesadas() : 0.0);

        row.setTaMinMs(m.getTaMinMs());
        row.setTaMaxMs(m.getTaMaxMs());
        row.setTaPromedioMs(m.getTaPromedioMs());
        row.setTiempoTotalAlgMs(m.getTiempoTotalAlgMs());
        row.setTiempoRealMs(tiempoRealMs);
        row.setTiempoRealMin(tiempoRealMs / 60_000.0);

        row.setBacklogPico(m.getBacklogPico());
        row.setBacklogActual(m.getBacklogActual());
        row.setSinRutaDefinitivo(m.getSinRutaDefinitivo());

        row.setColapsoDetectado(m.isCollapsoDetectado());
        row.setBloqueColapso(m.getBloqueColapso());
        row.setAdvertenciaCalibracion(m.isAdvertenciaCalibracion());
        return row;
    }

    /**
     * Calcula la recomendación de K para los escenarios 2 y 3 a partir de las filas.
     *
     * <p>Escenario 2: K más bajo del grid donde se cumplan
     * {@code 30 ≤ tiempoRealMin ≤ 90} ∧ {@code %SLA ≥ 0.95} ∧ {@code taMax ≤ 0.8 * Sa}.
     * Si nada cumple, relaja: el K cuyo tiempoRealMin sea más cercano a 60 con %SLA decente.
     *
     * <p>Escenario 3: K más alto donde {@code colapsoDetectado=true} con
     * {@code bloqueColapso} entre 30% y 70% del horizonte total.
     */
    private BenchmarkResult.Recomendacion calcularRecomendacion(BenchmarkResult result, int saMin) {
        BenchmarkResult.Recomendacion rec = new BenchmarkResult.Recomendacion();
        long saMs = saMin * 60_000L;

        // Promediar repeticiones por (escenario, K, cancelProb).
        // Tomamos cancelProb=0.0 si está disponible (caso "limpio") para escenario 2.
        var filasE2 = result.getFilas().stream()
                .filter(f -> "2".equals(f.getEscenario()))
                .toList();
        var filasE3 = result.getFilas().stream()
                .filter(f -> "3".equals(f.getEscenario()))
                .toList();

        // Escenario 2
        if (!filasE2.isEmpty()) {
            double cpPreferida = filasE2.stream().mapToDouble(BenchmarkRow::getCancelProb).min().orElse(0.0);
            BenchmarkRow optimo = filasE2.stream()
                    .filter(f -> f.getCancelProb() == cpPreferida)
                    .filter(f -> f.getTiempoRealMin() >= 30 && f.getTiempoRealMin() <= 90)
                    .filter(f -> f.getPorcentajeCumpleSla() >= 0.95)
                    .filter(f -> f.getTaMaxMs() <= saMs * 0.8)
                    .min(Comparator.comparingInt(BenchmarkRow::getK))
                    .orElse(null);
            if (optimo != null) {
                rec.setEscenario2K(optimo.getK());
                rec.setRazon2(String.format(
                        "K=%d cumple los 3 criterios: %.1f min real, %.1f%% SLA, Ta max=%dms (Sa=%dms)",
                        optimo.getK(), optimo.getTiempoRealMin(),
                        optimo.getPorcentajeCumpleSla() * 100,
                        optimo.getTaMaxMs(), saMs));
            } else {
                // Relajar: el más cercano a 60 min con %SLA ≥ 0.85
                BenchmarkRow fallback = filasE2.stream()
                        .filter(f -> f.getCancelProb() == cpPreferida)
                        .filter(f -> f.getPorcentajeCumpleSla() >= 0.85)
                        .min(Comparator.comparingDouble(f -> Math.abs(f.getTiempoRealMin() - 60.0)))
                        .orElse(null);
                if (fallback != null) {
                    rec.setEscenario2K(fallback.getK());
                    rec.setRazon2(String.format(
                            "Relajado: ningún K cumple estricto. K=%d → %.1f min, %.1f%% SLA",
                            fallback.getK(), fallback.getTiempoRealMin(),
                            fallback.getPorcentajeCumpleSla() * 100));
                } else {
                    rec.setRazon2("Ningún K probado cumple criterios mínimos — calibrar más operadores o ampliar grid");
                }
            }
        }

        // Escenario 3
        if (!filasE3.isEmpty()) {
            BenchmarkRow optimo = filasE3.stream()
                    .filter(BenchmarkRow::isColapsoDetectado)
                    .filter(f -> {
                        if (f.getTotalBloques() <= 0) return false;
                        double pos = (double) f.getBloqueColapso() / f.getTotalBloques();
                        return pos >= 0.30 && pos <= 0.70;
                    })
                    .max(Comparator.comparingInt(BenchmarkRow::getK))
                    .orElse(null);
            if (optimo != null) {
                double pos = (double) optimo.getBloqueColapso() / optimo.getTotalBloques();
                rec.setEscenario3K(optimo.getK());
                rec.setRazon3(String.format(
                        "K=%d colapsa en bloque %d/%d (%.0f%% del horizonte) con cancelProb=%.2f",
                        optimo.getK(), optimo.getBloqueColapso(), optimo.getTotalBloques(),
                        pos * 100, optimo.getCancelProb()));
            } else {
                BenchmarkRow fallback = filasE3.stream()
                        .filter(BenchmarkRow::isColapsoDetectado)
                        .max(Comparator.comparingInt(BenchmarkRow::getK))
                        .orElse(null);
                if (fallback != null) {
                    rec.setEscenario3K(fallback.getK());
                    rec.setRazon3(String.format(
                            "Relajado: K=%d colapsa pero fuera del rango óptimo (bloque %d/%d)",
                            fallback.getK(), fallback.getBloqueColapso(), fallback.getTotalBloques()));
                } else {
                    rec.setRazon3("Ningún K probado provocó colapso — subir cancelProb o agregar K mayores al grid");
                }
            }
        }
        return rec;
    }
}
