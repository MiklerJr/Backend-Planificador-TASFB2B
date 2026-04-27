package com.tasfb2b.planificador.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Registro en memoria de ejecuciones asíncronas del planificador.
 *
 * <p>Single-thread executor: solo una simulación corre a la vez para evitar
 * contención de CPU y conflictos en {@link com.tasfb2b.planificador.algorithm.alns.GreedyRepairOperator}
 * (cada simulación crea su propio enrutador, pero comparten CPU).
 */
@Slf4j
@Component
public class JobsRegistry {

    private final ConcurrentHashMap<String, JobState>      jobs    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>>     futures = new ConcurrentHashMap<>();
    private final ExecutorService                          executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "planificador-async");
                t.setDaemon(true);
                return t;
            });

    /** Crea un nuevo job y lo registra. Devuelve el jobId generado. */
    public JobState crear(String escenario, int k) {
        String jobId = UUID.randomUUID().toString();
        JobState job = new JobState(jobId, escenario, k);
        jobs.put(jobId, job);
        log.info("Job creado: {} (escenario={}, K={})", jobId, escenario, k);
        return job;
    }

    /** Ejecuta {@code task} en el executor y registra el Future. */
    public void ejecutar(JobState job, Runnable task) {
        Future<?> f = executor.submit(() -> {
            try {
                task.run();
                if (!"error".equals(job.estado)) job.estado = "completado";
            } catch (Exception ex) {
                job.estado = "error";
                job.error  = ex.getMessage();
                log.error("Job {} falló: {}", job.getJobId(), ex.getMessage(), ex);
            } finally {
                job.fin = java.time.LocalDateTime.now();
                futures.remove(job.getJobId());
            }
        });
        futures.put(job.getJobId(), f);
    }

    /** Devuelve el estado de un job o null si no existe. */
    public JobState get(String jobId) {
        return jobs.get(jobId);
    }

    /** Cancela una ejecución en curso. Devuelve true si se canceló. */
    public boolean cancelar(String jobId) {
        Future<?> f = futures.get(jobId);
        if (f == null) return false;
        boolean ok = f.cancel(true);
        if (ok) {
            JobState job = jobs.get(jobId);
            if (job != null) {
                job.estado = "error";
                job.error  = "cancelado por el usuario";
            }
        }
        return ok;
    }
}
