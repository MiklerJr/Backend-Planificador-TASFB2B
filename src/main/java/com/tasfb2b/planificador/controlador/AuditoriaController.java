package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.servicios.PlanificadorService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/planificador")
public class AuditoriaController {

    private final PlanificadorService service;

    public AuditoriaController(PlanificadorService service) {
        this.service = service;
    }

    @GetMapping(value = "/jobs/{jobId}/auditoria.zip")
    public ResponseEntity<?> auditoriaJob(
            @PathVariable String jobId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {

        if (service.getJob(jobId) == null) return ResponseEntity.notFound().build();

        PlanificadorService.ResultadoAuditoria r = service.generarAuditoriaZip(jobId, desde, hasta);
        if (!r.disponible()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", r.error()));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"auditoria_" + jobId + ".zip\"");
        headers.set("X-Audit-Rows", String.valueOf(r.filas()));
        // Si el rango pedido se recortó a la ventana realmente simulada, se informa el rango efectivo.
        if (r.recortado()) {
            headers.set("X-Audit-Range", r.desdeEfectivo() + "/" + r.hastaEfectivo());
        }

        Resource body = new FileSystemResource(r.path().toFile());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping(value = "/jobs/{jobId}/auditoria/dia")
    public ResponseEntity<?> auditoriaDia(
            @PathVariable String jobId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        LocalDateTime desde = fecha.atStartOfDay();
        LocalDateTime hasta = fecha.plusDays(1).atStartOfDay();
        return auditoriaJob(jobId, desde, hasta);   // reusa validación + clamp + headers
    }

    @GetMapping(value = "/jobs/{jobId}/auditoria/estimacion")
    public ResponseEntity<?> estimacionAuditoria(
            @PathVariable String jobId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {

        if (service.getJob(jobId) == null) return ResponseEntity.notFound().build();

        PlanificadorService.ResultadoEstimacion r = service.estimarAuditoria(jobId, desde, hasta);
        if (!r.disponible()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", r.error()));
        }
        return ResponseEntity.ok(r.estimacion());
    }
}
