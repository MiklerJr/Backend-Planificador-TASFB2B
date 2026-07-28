package com.tasfb2b.planificador.servicios.jobs;

import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

@Slf4j
@Component
public class RegistroJobs {

    public static final Set<String> ESTADOS_ACTIVOS = Set.of("encolado", "calentando", "ejecutando");

    private final ConcurrentHashMap<String, EstadoJob>      jobs    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>>     futures = new ConcurrentHashMap<>();
    private final ExecutorService                          executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "planificador-async");
                t.setDaemon(true);
                return t;
            });

    private final int maxJobsEnMemoria;

    @Autowired
    public RegistroJobs(PlanificadorProperties props) {
        this.maxJobsEnMemoria = props.getScenario().getMaxJobsEnMemoria();
    }

    public RegistroJobs() {
        this.maxJobsEnMemoria = 3;
    }

    public EstadoJob crear(String escenario, int k) {
        String jobId = UUID.randomUUID().toString();
        EstadoJob job = new EstadoJob(jobId, escenario, k);
        jobs.put(jobId, job);
        log.info("Job creado: {} (escenario={}, K={}) — estado=encolado", jobId, escenario, k);
        purgarJobsViejos();
        purgarZipsViejos();
        return job;
    }

    public void purgarZipsViejos() {
        for (EstadoJob j : jobs.values()) {
            if (!ESTADOS_ACTIVOS.contains(j.estado)) j.borrarZip();
        }
    }

    public void purgarJobsViejos() {
        List<EstadoJob> terminados = new ArrayList<>();
        for (EstadoJob j : jobs.values()) {
            if (!ESTADOS_ACTIVOS.contains(j.estado) && j.fin != null) terminados.add(j);
        }
        if (terminados.size() <= maxJobsEnMemoria) return;
        terminados.sort(Comparator.comparing((EstadoJob j) -> j.fin).reversed());
        for (int i = maxJobsEnMemoria; i < terminados.size(); i++) {
            terminados.get(i).liberarPesados();
        }
    }

    public void ejecutar(EstadoJob job, Runnable task) {
        Future<?> f = executor.submit(() -> {
            if ("encolado".equals(job.estado)) job.estado = "ejecutando";
            try {
                task.run();
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
                job.finRealMs = System.currentTimeMillis();
                futures.remove(job.getJobId());
            }
        });
        futures.put(job.getJobId(), f);
    }

    public void ejecutarTarea(Runnable task) {
        executor.submit(task);
    }

    public EstadoJob get(String jobId) {
        return jobs.get(jobId);
    }

    public int cantidadJobs() {
        return jobs.size();
    }

    public boolean cancelar(String jobId) {
        EstadoJob job = jobs.get(jobId);
        if (job == null) return false;

        Future<?> f = futures.get(jobId);
        if (f != null) {
            boolean ok = f.cancel(true);
            if (ok) {
                job.estado = "cancelado";
                job.canceladoPorUsuario = true;
            }
            return ok;
        }
        if ("encolado".equals(job.estado)) {
            job.estado = "cancelado";
            job.canceladoPorUsuario = true;
            return true;
        }
        return false;
    }

    private static final Comparator<EstadoJob> POR_CREACION =
            Comparator.comparing(EstadoJob::getInicio).thenComparingLong(EstadoJob::getOrdenCreacion);

    public List<EstadoJob> listarActivos() {
        List<EstadoJob> activos = new ArrayList<>();
        for (EstadoJob j : jobs.values()) {
            if (ESTADOS_ACTIVOS.contains(j.estado)) activos.add(j);
        }
        activos.sort(POR_CREACION);
        return activos;
    }

    public boolean haySimulacionEnCurso() {
        for (EstadoJob j : jobs.values()) {
            if ("ejecutando".equals(j.estado) || "calentando".equals(j.estado)) return true;
        }
        return false;
    }

    public List<EstadoJob> listarTodos() {
        List<EstadoJob> todos = new ArrayList<>(jobs.values());
        todos.sort(POR_CREACION);
        return todos;
    }

    public int posicionEnCola(String jobId) {
        EstadoJob target = jobs.get(jobId);
        if (target == null || !"encolado".equals(target.estado)) return 0;
        int posicion = 1;
        for (EstadoJob j : jobs.values()) {
            if (j == target) continue;
            if ("encolado".equals(j.estado) && j.getInicio().isBefore(target.getInicio())) {
                posicion++;
            }
        }
        return posicion;
    }
}
