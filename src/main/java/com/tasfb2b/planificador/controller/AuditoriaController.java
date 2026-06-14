package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.services.JobState;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;

/**
 * Descarga de la auditoría de un job (Tanda 1D: extraído de {@code PlanificadorController}).
 * Ruta inalterada ({@code /api/planificador/jobs/{jobId}/auditoria.zip}); CORS lo aporta el
 * {@code CorsFilter} global, que ya expone {@code X-Audit-Rows} y {@code Content-Disposition}.
 */
@RestController
@RequestMapping("/api/planificador")
public class AuditoriaController {

    private final PlanificadorService service;

    public AuditoriaController(PlanificadorService service) {
        this.service = service;
    }

    /**
     * Descarga la auditoría de un job completado como un ZIP de varios CSV
     * (hasta 50000 filas por archivo; un único CSV para millones de envíos no es
     * práctico). Cada CSV interno se llama {@code <jobId>-<inicio>-<fin>.csv} con
     * el rango de fechaHoraInicio (registro) de su contenido, y trae 25 columnas
     * con la validación formal por envío de las restricciones del cliente
     * (cumpleSLA, sinCiclos, escalaMinOK, capacidad, almacén, score 0-100) y los
     * timestamps ISO de inicio (readyTime) y fin del envío (llegada + DEST_STORAGE_MIN).
     *
     * <p>Devuelve 204 si el job aún ejecuta (ZIP no disponible), 404 si no existe.
     */
    @GetMapping(value = "/jobs/{jobId}/auditoria.zip", produces = "application/zip")
    public ResponseEntity<?> auditoriaJob(@PathVariable String jobId) {
        JobState job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        if (job.auditoriaZipPath == null || !Files.exists(job.auditoriaZipPath)) {
            return ResponseEntity.noContent().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"auditoria_" + jobId + ".zip\"");
        headers.set("X-Audit-Rows", String.valueOf(job.auditoriaFilas));

        Resource body = new FileSystemResource(job.auditoriaZipPath.toFile());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
