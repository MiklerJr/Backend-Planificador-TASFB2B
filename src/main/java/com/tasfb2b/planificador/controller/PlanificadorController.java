package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.services.JobState;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
@RestController
@RequestMapping("/api/planificador")
public class PlanificadorController {

    private final PlanificadorService service;

    public PlanificadorController(PlanificadorService service) {
        this.service = service;
    }

    /**
     * Ejecuta la planificación de pedidos-rutas.
     *
     * @param algoritmo  "alns" (único motor soportado actualmente)
     * @param k          Factor de aceleración de la simulación:
     *                   K=1  → operaciones día a día (tiempo real)
     *                   K=14 → simulación de 3 días (default)
     *                   K=75 → simulación hasta el colapso logístico
     */
    /**
     * Ejecuta la planificación de pedidos-rutas.
     *
     * @param algoritmo   "alns" (default) o "aco"
     * @param k           Factor de aceleración: K=1 día a día, K=14 sim-3días (default), K=75 colapso
     * @param cancelProb  Probabilidad de cancelación de vuelo-día [0.0–1.0], default 0
     */
    @GetMapping("/ejecutar")
    public ResponseEntity<SimulacionResponse> ejecutar(
            @RequestParam(defaultValue = "alns") String algoritmo,
            @RequestParam(defaultValue = "14")   int    k,
            @RequestParam(defaultValue = "0.0")  double cancelProb) {

        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb)); // clamp al rango válido
        return switch (algoritmo.toLowerCase()) {
            case "alns" -> ResponseEntity.ok(service.ejecutarALNS(k, cancelProb));
            default     -> ResponseEntity.badRequest().build();
        };
    }

    @GetMapping("/bloque/{index}")
    public ResponseEntity<SimulacionResponse.BloqueSimulacion> getBloque(@PathVariable int index) {
        SimulacionResponse.BloqueSimulacion bloque = service.getBloque(index);
        if (bloque == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(bloque);
    }

    // ── Escenario 3: hasta el colapso ────────────────────────────────────────

    @GetMapping("/ejecutar-colapso")
    public ResponseEntity<SimulacionResponse> ejecutarColapso(
            @RequestParam(defaultValue = "75")   int    k,
            @RequestParam(defaultValue = "0.1")  double cancelProb,
            @RequestParam(defaultValue = "0.20") double umbralColapso) {

        cancelProb    = Math.max(0.0, Math.min(1.0, cancelProb));
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        return ResponseEntity.ok(service.ejecutarHastaColapso(k, cancelProb, umbralColapso));
    }

    // ── Escenario 1: día a día ────────────────────────────────────────────────

    @PostMapping("/escenario1/inicializar")
    public ResponseEntity<Map<String, Object>> inicializarEsc1(
            @RequestParam(defaultValue = "0.0") double cancelProb) {

        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb));
        return ResponseEntity.ok(service.inicializarEscenario1(cancelProb));
    }

    @GetMapping("/escenario1/ventana")
    public ResponseEntity<SimulacionResponse.BloqueSimulacion> siguienteVentana() {
        try {
            SimulacionResponse.BloqueSimulacion bloque = service.procesarSiguienteVentana();
            if (bloque == null) return ResponseEntity.noContent().build(); // 204 = fin
            return ResponseEntity.ok(bloque);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/escenario1/estado")
    public ResponseEntity<Map<String, Object>> estadoEsc1() {
        return ResponseEntity.ok(service.getEstadoEscenario1());
    }

    @GetMapping("/escenario1/bloque/{index}")
    public ResponseEntity<SimulacionResponse.BloqueSimulacion> getBloqueEsc1(@PathVariable int index) {
        SimulacionResponse.BloqueSimulacion bloque = service.getBloqueEsc1(index);
        if (bloque == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(bloque);
    }

    // ── Escenarios 2/3 asíncronos ────────────────────────────────────────────
    // Soportan ejecuciones largas (30-90 min con sleep activo) sin bloquear el HTTP.

    @PostMapping("/escenario2/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarEsc2(
            @RequestParam(defaultValue = "14")    int    k,
            @RequestParam(defaultValue = "0.0")   double cancelProb,
            @RequestParam(defaultValue = "alns")  String algoritmo,
            @RequestParam(required = false)        Long  seed,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fechaInicio) {
        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb));
        JobState job = service.iniciarEscenario2Async(k, cancelProb, algoritmo, seed, fechaInicio);
        Map<String, Object> body = new HashMap<>();
        body.put("jobId",     job.getJobId());
        body.put("escenario", "2");
        body.put("algoritmo", job.algoritmo);
        body.put("k",         k);
        body.put("seed",      job.seed);
        body.put("estado",    job.estado);
        if (job.fechaInicio != null) body.put("fechaInicio", job.fechaInicio.toString());
        return ResponseEntity.accepted().body(body);
    }

    @PostMapping("/escenario3/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarEsc3(
            @RequestParam(defaultValue = "75")    int    k,
            @RequestParam(defaultValue = "0.1")   double cancelProb,
            @RequestParam(defaultValue = "0.20")  double umbralColapso,
            @RequestParam(defaultValue = "alns")  String algoritmo,
            @RequestParam(required = false)        Long  seed) {
        cancelProb    = Math.max(0.0, Math.min(1.0, cancelProb));
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        JobState job = service.iniciarEscenario3Async(k, cancelProb, umbralColapso, algoritmo, seed);
        return ResponseEntity.accepted().body(Map.of(
                "jobId",         job.getJobId(),
                "escenario",     "3",
                "algoritmo",     job.algoritmo,
                "k",             k,
                "seed",          job.seed,
                "umbralColapso", umbralColapso,
                "estado",        job.estado
        ));
    }

    @GetMapping("/jobs/{jobId}/estado")
    public ResponseEntity<Map<String, Object>> estadoJob(@PathVariable String jobId) {
        JobState job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        Map<String, Object> body = new HashMap<>();
        body.put("jobId",         job.getJobId());
        body.put("escenario",     job.getEscenario());
        body.put("algoritmo",     job.algoritmo);
        body.put("seed",          job.seed);
        if (job.fechaInicio != null) body.put("fechaInicio", job.fechaInicio.toString());
        body.put("k",             job.getK());
        body.put("estado",        job.estado);
        body.put("bloqueActual",  job.bloqueActual);
        body.put("totalBloques",  job.totalBloques);
        body.put("progreso",      job.getProgreso());
        body.put("taPromedioMs",  job.taPromedioMs);
        body.put("inicio",        job.inicio.toString());
        if (job.fin != null) body.put("fin", job.fin.toString());
        if (job.error != null) body.put("error", job.error);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/resultado")
    public ResponseEntity<SimulacionResponse> resultadoJob(@PathVariable String jobId) {
        JobState job = service.getJob(jobId);
        if (job == null)             return ResponseEntity.notFound().build();
        if (job.resultado == null)   return ResponseEntity.noContent().build(); // 204 = aún ejecutando
        return ResponseEntity.ok(job.resultado);
    }

    @PostMapping("/jobs/{jobId}/cancelar")
    public ResponseEntity<Map<String, Object>> cancelarJob(@PathVariable String jobId) {
        boolean ok = service.cancelarJob(jobId);
        return ResponseEntity.ok(Map.of("jobId", jobId, "cancelado", ok));
    }

    /**
     * Descarga el CSV con la muestra de hasta 25 envíos. Solo disponible para
     * jobs de escenario 2 con motor ALNS (requisito del cliente).
     *
     * <p>10 columnas: idMaleta, idCliente, origen, destino, cantidad, ruta,
     * tiempoTotalMin, slaLimiteMin, cumpleSLA, tardado.
     */
    @GetMapping(value = "/jobs/{jobId}/muestra.csv", produces = "text/csv")
    public ResponseEntity<byte[]> muestraJob(@PathVariable String jobId) {
        JobState job = service.getJob(jobId);
        if (job == null)             return ResponseEntity.notFound().build();
        if (job.muestraCsv == null)  return ResponseEntity.noContent().build();

        byte[] body = job.muestraCsv.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"muestra_" + jobId + ".csv\"");
        headers.set("X-Muestra-Rows", String.valueOf(job.muestraFilas));
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * Descarga el CSV de auditoría de un job completado. 23 columnas con
     * validación formal por envío de las restricciones del cliente
     * (cumpleSLA, sinCiclos, escalaMinOK, capacidad, almacén, score 0-100).
     *
     * <p>Devuelve 204 si el job aún ejecuta, 404 si no existe.
     */
    @GetMapping(value = "/jobs/{jobId}/auditoria.csv", produces = "text/csv")
    public ResponseEntity<byte[]> auditoriaJob(@PathVariable String jobId) {
        JobState job = service.getJob(jobId);
        if (job == null)              return ResponseEntity.notFound().build();
        if (job.auditoriaCsv == null) return ResponseEntity.noContent().build();

        byte[] body = job.auditoriaCsv.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"auditoria_" + jobId + ".csv\"");
        headers.set("X-Audit-Rows", String.valueOf(job.auditoriaFilas));
        return ResponseEntity.ok().headers(headers).body(body);
    }
}