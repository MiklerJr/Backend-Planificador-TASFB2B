package com.tasfb2b.planificador.servicios.trabajos;

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
public class RegistroTrabajos {

    public static final Set<String> ESTADOS_ACTIVOS = Set.of("encolado", "calentando", "ejecutando");

    private final ConcurrentHashMap<String, EstadoTrabajo>      jobs    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>>     futures = new ConcurrentHashMap<>();
    private final ExecutorService                          executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "planificador-async");
                t.setDaemon(true);
                return t;
            });

    private final int maxJobsEnMemoria;

    @Autowired
    public RegistroTrabajos(PlanificadorProperties props) {
        this.maxJobsEnMemoria = props.getScenario().getMaxJobsEnMemoria();
    }

    public RegistroTrabajos() {
        this.maxJobsEnMemoria = 3;
    }

    public EstadoTrabajo crear(String escenario, int k) {
        String jobId = UUID.randomUUID().toString();
        EstadoTrabajo job = new EstadoTrabajo(jobId, escenario, k);
        jobs.put(jobId, job);
        log.info("Job creado: {} (escenario={}, K={}) — estado=encolado", jobId, escenario, k);
        purgarJobsViejos();   // anti-OOM (RAM): libera los pesados de corridas anteriores ya terminadas
        purgarZipsViejos();   // anti-OOM (disco): borra los ZIP de auditoría de corridas anteriores
        return job;
    }

    public void purgarZipsViejos() {
        for (EstadoTrabajo j : jobs.values()) {
            if (!ESTADOS_ACTIVOS.contains(j.estado)) j.borrarZip();
        }
    }

    public void purgarJobsViejos() {
        List<EstadoTrabajo> terminados = new ArrayList<>();
        for (EstadoTrabajo j : jobs.values()) {
            if (!ESTADOS_ACTIVOS.contains(j.estado) && j.fin != null) terminados.add(j);
        }
        if (terminados.size() <= maxJobsEnMemoria) return;
        terminados.sort(Comparator.comparing((EstadoTrabajo j) -> j.fin).reversed());
        for (int i = maxJobsEnMemoria; i < terminados.size(); i++) {
            terminados.get(i).liberarPesados();
        }
    }

    public void ejecutar(EstadoTrabajo job, Runnable task) {
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
                futures.remove(job.getJobId());
            }
        });
        futures.put(job.getJobId(), f);
    }

    public void ejecutarTarea(Runnable task) {
        executor.submit(task);
    }

    public EstadoTrabajo get(String jobId) {
        return jobs.get(jobId);
    }

    public int cantidadJobs() {
        return jobs.size();
    }

    public boolean cancelar(String jobId) {
        EstadoTrabajo job = jobs.get(jobId);
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

    public List<EstadoTrabajo> listarActivos() {
        List<EstadoTrabajo> activos = new ArrayList<>();
        for (EstadoTrabajo j : jobs.values()) {
            if (ESTADOS_ACTIVOS.contains(j.estado)) activos.add(j);
        }
        activos.sort(Comparator.comparing(EstadoTrabajo::getInicio));
        return activos;
    }

    public List<EstadoTrabajo> listarTodos() {
        List<EstadoTrabajo> todos = new ArrayList<>(jobs.values());
        todos.sort(Comparator.comparing(EstadoTrabajo::getInicio));
        return todos;
    }

    public int posicionEnCola(String jobId) {
        EstadoTrabajo target = jobs.get(jobId);
        if (target == null || !"encolado".equals(target.estado)) return 0;
        int posicion = 1; // el propio job ocupa al menos posición 1
        for (EstadoTrabajo j : jobs.values()) {
            if (j == target) continue;
            if ("encolado".equals(j.estado) && j.getInicio().isBefore(target.getInicio())) {
                posicion++;
            }
        }
        return posicion;
    }
}
