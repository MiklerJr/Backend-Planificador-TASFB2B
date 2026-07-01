package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.services.PlanificadorService;
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

/**
 * Descarga de la auditoría de un job. Ruta {@code /api/planificador/jobs/{jobId}/auditoria.zip};
 * CORS lo aporta el {@code CorsFilter} global, que ya expone {@code X-Audit-Rows} y
 * {@code Content-Disposition}.
 */
@RestController
@RequestMapping("/api/planificador")
public class AuditoriaController {

    private final PlanificadorService service;

    public AuditoriaController(PlanificadorService service) {
        this.service = service;
    }

    /**
     * Descarga la auditoría de un job terminado como un ZIP de varios CSV (hasta 50000 filas por
     * archivo; un único CSV para millones de envíos no es práctico). Cada CSV interno se llama
     * {@code <jobId>-<inicio>-<fin>.csv} con el rango de fechaHoraInicio (registro) de su contenido,
     * y trae 25 columnas con los datos del envío y la validación formal por envío (cumpleSLA,
     * sinCiclos, escalaMinOK, score 0-100) y los timestamps ISO de inicio/fin.
     *
     * <p><b>El ZIP se genera SOLO al pedirlo</b> (ya no automáticamente al terminar el job): así no se
     * produce una auditoría que nadie quiere ni se bloquea el motor escribiéndola. La generación puede
     * tardar (lee la solución de BD en streaming): el front debe mostrar una pantalla de carga mientras
     * dura la descarga.
     *
     * <p><b>Filtro por fecha (opcional):</b> {@code desde}/{@code hasta} son instantes UTC ISO-8601
     * (p. ej. {@code 2027-11-01T00:00}) sobre el {@code readyTime} del envío; {@code desde} inclusivo,
     * {@code hasta} exclusivo. Sin parámetros = auditoría COMPLETA.
     *
     * @return {@code 200} con el ZIP · {@code 404} si el job no existe · {@code 409} si el job aún está
     *         activo o su solución ya fue reemplazada por otra corrida (cuerpo {@code {"error": ...}}).
     */
    // Sin `produces`: el 200 fija el Content-Type a application/zip por header, y el 409 devuelve JSON
    // ({"error":...}). Si se fijara produces="application/zip", el cuerpo JSON del 409 no tendría
    // convertidor compatible y Spring respondería 500.
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

    /**
     * Auditoría de UN DÍA concreto (atajo cómodo sobre {@link #auditoriaJob}): el {@code fecha}
     * (ISO {@code YYYY-MM-DD}) se interpreta en <b>UTC</b> y se traduce a {@code desde = fecha T00:00},
     * {@code hasta = fecha+1 T00:00}. Hereda la verificación contra la ventana simulada (400 si el día
     * queda fuera, header {@code X-Audit-Range} si se recorta) y trae las cancelaciones de ESE día.
     *
     * @return mismo contrato que {@code auditoria.zip} (200 ZIP · 404 · 409 · 400 si el día no se solapa).
     */
    @GetMapping(value = "/jobs/{jobId}/auditoria/dia")
    public ResponseEntity<?> auditoriaDia(
            @PathVariable String jobId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        LocalDateTime desde = fecha.atStartOfDay();
        LocalDateTime hasta = fecha.plusDays(1).atStartOfDay();
        return auditoriaJob(jobId, desde, hasta);   // reusa validación + clamp + headers
    }

    /**
     * Estima —SIN generar el ZIP— cuántos CSV tendría la auditoría del job en el rango opcional
     * {@code desde}/{@code hasta} (UTC, mismo eje que {@code auditoria.zip}): archivos de envíos y de
     * cancelaciones, con sus filas. Pensado para que el front avise del tamaño antes de descargar.
     *
     * @return {@code 200} con {@link com.tasfb2b.planificador.dto.auditoria.EstimacionAuditoria} · {@code 404} si
     *         el job no existe · {@code 409} si aún activo o su solución fue reemplazada · {@code 400}
     *         si el rango no se solapa con la ventana simulada.
     */
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
