package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.BenchmarkRequest;
import com.tasfb2b.planificador.dto.BenchmarkResult;
import com.tasfb2b.planificador.services.BenchmarkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Endpoints REST para benchmark de calibración del planificador.
 *
 * <p>Flujo típico:
 * <ol>
 *   <li>{@code POST /run} → devuelve {jobId} y comienza a iterar el grid en background.</li>
 *   <li>{@code GET /{jobId}/estado} → progreso (filasCompletadas/filasTotales, configActual).</li>
 *   <li>{@code GET /{jobId}/resultado} → tabla completa + recomendación cuando estado="completado".</li>
 * </ol>
 *
 * <p>Las corridas son <b>secuenciales</b> (single-thread) para mantener Ta comparable.
 */
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
@RestController
@RequestMapping("/api/planificador/benchmark")
public class BenchmarkController {

    private final BenchmarkService service;

    public BenchmarkController(BenchmarkService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) BenchmarkRequest request) {
        if (request == null) request = new BenchmarkRequest();
        BenchmarkResult res = service.iniciar(request);
        return ResponseEntity.accepted().body(Map.of(
                "jobId",        res.getJobId(),
                "estado",       res.getEstado(),
                "filasTotales", res.getFilasTotales()
        ));
    }

    @GetMapping("/{jobId}/estado")
    public ResponseEntity<Map<String, Object>> estado(@PathVariable String jobId) {
        BenchmarkResult res = service.get(jobId);
        if (res == null) return ResponseEntity.notFound().build();

        Map<String, Object> body = new HashMap<>();
        body.put("jobId",            res.getJobId());
        body.put("estado",           res.getEstado());
        body.put("filasCompletadas", res.getFilasCompletadas());
        body.put("filasTotales",     res.getFilasTotales());
        body.put("progreso", res.getFilasTotales() > 0
                ? (double) res.getFilasCompletadas() / res.getFilasTotales()
                : 0.0);
        if (res.getConfigActual() != null) body.put("configActual", res.getConfigActual());
        if (res.getInicio() != null)       body.put("inicio",       res.getInicio().toString());
        if (res.getFin() != null)          body.put("fin",          res.getFin().toString());
        if (res.getError() != null)        body.put("error",        res.getError());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{jobId}/resultado")
    public ResponseEntity<BenchmarkResult> resultado(@PathVariable String jobId) {
        BenchmarkResult res = service.get(jobId);
        if (res == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(res);
    }
}
