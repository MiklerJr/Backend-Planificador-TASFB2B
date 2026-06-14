package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.ComparativaRequest;
import com.tasfb2b.planificador.dto.ComparativaResultado;
import com.tasfb2b.planificador.services.ComparativaService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Endpoints REST para comparativa pareada ALNS vs ACO.
 *
 * <p>Flujo Postman:
 * <ol>
 *   <li>{@code POST /run} → devuelve {jobId} y arranca todos los pares en background.</li>
 *   <li>{@code GET /{jobId}/estado} → progreso (filasCompletadas/filasTotales).</li>
 *   <li>{@code GET /{jobId}/resultado.csv} → CSV con todas las filas pareadas para Wilcoxon.</li>
 * </ol>
 *
 * <p>Las corridas son <b>secuenciales</b> (single-thread) para que las mediciones
 * de Ta/tiempo real sean comparables sin contención de CPU entre simulaciones.
 *
 * <p>CORS lo aporta el {@code CorsFilter} global de {@code PlanificadorApplication} (fuente única
 * desde la Tanda 1D); por eso este controller ya no lleva {@code @CrossOrigin}.
 */
@RestController
@RequestMapping("/api/planificador/comparativa")
public class ComparativaController {

    private final ComparativaService service;

    public ComparativaController(ComparativaService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) ComparativaRequest request) {
        if (request == null) request = new ComparativaRequest();
        ComparativaResultado res = service.iniciar(request);
        return ResponseEntity.accepted().body(Map.of(
                "jobId",        res.getJobId(),
                "estado",       res.getEstado(),
                "filasTotales", res.getFilasTotales()
        ));
    }

    @GetMapping("/{jobId}/estado")
    public ResponseEntity<Map<String, Object>> estado(@PathVariable String jobId) {
        ComparativaResultado res = service.get(jobId);
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
    public ResponseEntity<ComparativaResultado> resultado(@PathVariable String jobId) {
        ComparativaResultado res = service.get(jobId);
        if (res == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(res);
    }

    /**
     * CSV con observaciones pareadas listo para análisis estadístico
     * (Wilcoxon de rangos con signo). Una fila por par (escenario, rep, seed).
     */
    @GetMapping(value = "/{jobId}/resultado.csv", produces = "text/csv")
    public ResponseEntity<byte[]> resultadoCsv(@PathVariable String jobId) {
        ComparativaResultado res = service.get(jobId);
        if (res == null) return ResponseEntity.notFound().build();
        String csv = service.aCsv(jobId);
        if (csv == null) return ResponseEntity.notFound().build();

        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"comparativa_" + jobId + ".csv\"");
        headers.set("X-Rows", String.valueOf(res.getFilas().size()));
        headers.set("X-Estado", res.getEstado());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
