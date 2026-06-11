package com.tasfb2b.planificador.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
 *
 * <p>Estados posibles de un {@link JobState}:
 * <ul>
 *   <li>{@code "encolado"} — creado, esperando turno en el executor.
 *   <li>{@code "calentando"} — corriendo el warm-up previo a fechaInicio.
 *   <li>{@code "ejecutando"} — procesando el plan visible.
 *   <li>{@code "completado"} — terminó OK.
 *   <li>{@code "cancelado"} — el usuario llamó a /cancelar.
 *   <li>{@code "error"} — falló por excepción no controlada.
 * </ul>
 */
@Slf4j
@Component
public class JobsRegistry {

    /** Estados que el front considera "vivos" (incluibles en /jobs?activos=true). */
    public static final Set<String> ESTADOS_ACTIVOS = Set.of("encolado", "calentando", "ejecutando");

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
        log.info("Job creado: {} (escenario={}, K={}) — estado=encolado", jobId, escenario, k);
        return job;
    }

    /**
     * Ejecuta {@code task} en el executor y registra el Future. El wrapper
     * transiciona el estado del job a {@code "ejecutando"} en el momento en
     * que el worker realmente lo desencola (no antes), para que el front
     * pueda distinguir "encolado esperando" vs "corriendo".
     */
    public void ejecutar(JobState job, Runnable task) {
        Future<?> f = executor.submit(() -> {
            // Punto de transición: el worker tomó el job de la cola.
            // El task (ejecutarALNS) puede después sobreescribir a "calentando"
            // si hay warm-up; en otro caso queda en "ejecutando".
            if ("encolado".equals(job.estado)) job.estado = "ejecutando";
            try {
                task.run();
                // No sobreescribir si el task ya terminó en estado terminal
                // (cancelado / error).
                if ("calentando".equals(job.estado) || "ejecutando".equals(job.estado)) {
                    job.estado = "completado";
                }
            } catch (Throwable ex) {
                if (!"cancelado".equals(job.estado)) {
                    job.estado = "error";
                    String message = ex.getMessage();
                    job.error = ex.getClass().getSimpleName() + (message != null ? ": " + message : "");
                }
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

    /**
     * Cancela una ejecución en curso. Devuelve true si se canceló.
     *
     * <p>Setea {@code estado="cancelado"} (NO {@code "error"}) y la flag
     * {@code canceladoPorUsuario=true} para que el front pueda distinguir
     * una cancelación voluntaria de un fallo real.
     */
    public boolean cancelar(String jobId) {
        JobState job = jobs.get(jobId);
        if (job == null) return false;

        // Caso 1: job activo con Future en ejecución → cancelar el Future.
        Future<?> f = futures.get(jobId);
        if (f != null) {
            boolean ok = f.cancel(true);
            if (ok) {
                job.estado = "cancelado";
                job.canceladoPorUsuario = true;
            }
            return ok;
        }
        // Caso 2: job aún encolado (Future no presente porque cancelar() corre
        // antes de que el worker lo tome). Marcarlo igualmente.
        if ("encolado".equals(job.estado)) {
            job.estado = "cancelado";
            job.canceladoPorUsuario = true;
            return true;
        }
        return false;
    }

    /**
     * Lista los jobs en estado activo ({@link #ESTADOS_ACTIVOS}), ordenados
     * por orden de creación. Permite al front recuperar simulaciones en marcha
     * tras un refresh sin necesidad de persistir el jobId en cliente.
     */
    public List<JobState> listarActivos() {
        List<JobState> activos = new ArrayList<>();
        for (JobState j : jobs.values()) {
            if (ESTADOS_ACTIVOS.contains(j.estado)) activos.add(j);
        }
        activos.sort(Comparator.comparing(JobState::getInicio));
        return activos;
    }

    /** Lista todos los jobs (activos y terminados), ordenados por inicio. */
    public List<JobState> listarTodos() {
        List<JobState> todos = new ArrayList<>(jobs.values());
        todos.sort(Comparator.comparing(JobState::getInicio));
        return todos;
    }

    /**
     * Posición del job en la cola del executor (1-based). Devuelve 0 si el
     * job ya está corriendo, terminó, o no existe. Cuenta los jobs en estado
     * {@code "encolado"} creados antes que el dado.
     */
    public int posicionEnCola(String jobId) {
        JobState target = jobs.get(jobId);
        if (target == null || !"encolado".equals(target.estado)) return 0;
        int posicion = 1; // el propio job ocupa al menos posición 1
        for (JobState j : jobs.values()) {
            if (j == target) continue;
            if ("encolado".equals(j.estado) && j.getInicio().isBefore(target.getInicio())) {
                posicion++;
            }
        }
        return posicion;
    }
}
