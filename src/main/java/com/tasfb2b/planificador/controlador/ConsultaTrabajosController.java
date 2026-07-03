package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.dto.trabajos.AlertaColapso;
import com.tasfb2b.planificador.dto.almacenes.*;
import com.tasfb2b.planificador.dto.trabajos.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.dto.vuelos.VuelosUsadosResponse;
import com.tasfb2b.planificador.servicios.trabajos.ConsultaTrabajosService;
import com.tasfb2b.planificador.servicios.trabajos.EstadoTrabajo;
import com.tasfb2b.planificador.servicios.PlanificadorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/planificador")
public class ConsultaTrabajosController {

    private final PlanificadorService service;
    private final ConsultaTrabajosService jobQuery;

    public ConsultaTrabajosController(PlanificadorService service, ConsultaTrabajosService jobQuery) {
        this.service = service;
        this.jobQuery = jobQuery;
    }

    @GetMapping("/jobs")
    public ResponseEntity<ListaTrabajosResponse> listarJobs(
            @RequestParam(defaultValue = "true") boolean activos) {
        return ResponseEntity.ok(service.listarJobsResponse(activos));
    }

    @GetMapping("/jobs/{jobId}/estado-inicial")
    public ResponseEntity<EstadoInicialResponse> estadoInicialJob(@PathVariable String jobId) {
        EstadoTrabajo job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.estadoInicial == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(service.buildEstadoInicialResponse(job));
    }

    @GetMapping("/jobs/{jobId}/almacenes/serie")
    public ResponseEntity<SerieAlmacenesResponse> serieAlmacenesJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde) {
        SerieAlmacenesResponse body = service.getSerieAlmacenes(jobId, desde);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/estado")
    public ResponseEntity<EstadoTrabajoResponse> estadoJob(@PathVariable String jobId) {
        EstadoTrabajoResponse body = service.getEstadoJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/alerta-colapso")
    public ResponseEntity<AlertaColapso> alertaColapsoJob(@PathVariable String jobId) {
        EstadoTrabajo job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(job.alertaColapso != null ? job.alertaColapso : AlertaColapso.verde());
    }

    @GetMapping("/jobs/{jobId}/dashboard")
    public ResponseEntity<TableroResponse> dashboardJob(@PathVariable String jobId) {
        TableroResponse body = jobQuery.getDashboardJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/indicadores")
    public ResponseEntity<IndicadoresResponse> indicadoresJob(@PathVariable String jobId) {
        IndicadoresResponse body = jobQuery.getIndicadoresJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/vuelos/carga")
    public ResponseEntity<CargaVuelosResponse> cargaVuelosJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde,
            @RequestParam(defaultValue = "0") int limit) {
        CargaVuelosResponse body = jobQuery.getCargaVuelosJob(jobId, desde, limit);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/vuelos/usados")
    public ResponseEntity<VuelosUsadosResponse> vuelosUsadosJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde) {
        VuelosUsadosResponse body = jobQuery.getVuelosUsadosJob(jobId, desde);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/almacenes/ocupacion")
    public ResponseEntity<OcupacionAlmacenesResponse> ocupacionAlmacenesJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde,
            @RequestParam(defaultValue = "0") int limit) {
        OcupacionAlmacenesResponse body = jobQuery.getOcupacionAlmacenesJob(jobId, desde, limit);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/asignaciones")
    public ResponseEntity<AsignacionesResponse> asignacionesJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde,
            @RequestParam(required = false) String aeropuerto,
            @RequestParam(required = false) String vueloId,
            @RequestParam(defaultValue = "false") boolean soloEnrutadas) {
        AsignacionesResponse body = jobQuery.getAsignacionesJob(jobId, desde, aeropuerto, vueloId, soloEnrutadas);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/envios/{idEnvio}")
    public ResponseEntity<EnvioEstadoResponse> envioJob(
            @PathVariable String jobId, @PathVariable String idEnvio,
            @RequestParam(required = false) String en) {
        if (service.getJob(jobId) == null) return ResponseEntity.notFound().build();   // 404 job
        java.time.LocalDateTime instante;
        try {
            instante = (en == null || en.isBlank()) ? null : java.time.LocalDateTime.parse(en);
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().build();                                // 400 'en' inválido
        }
        EnvioEstadoResponse estado = service.buscarEstadoEnvio(jobId, idEnvio, instante);
        if (estado == null) return ResponseEntity.notFound().build();                  // 404 envío
        return ResponseEntity.ok(estado);
    }

    @GetMapping("/jobs/{jobId}/bloques")
    public ResponseEntity<Map<String, Object>> bloquesJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde) {
        EstadoTrabajo job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        Map<String, Object> body = new HashMap<>();
        body.put("bloques",   job.bloquesDesde(desde));
        body.put("total",     job.bloquesPublicados());
        body.put("terminado", !"encolado".equals(job.estado)
                           && !"calentando".equals(job.estado)
                           && !"ejecutando".equals(job.estado));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/resultado")
    public ResponseEntity<SimulacionResponse> resultadoJob(@PathVariable String jobId) {
        EstadoTrabajo job = service.getJob(jobId);
        if (job == null)             return ResponseEntity.notFound().build();
        if (job.resultado == null)   return ResponseEntity.noContent().build(); // 204 = aún ejecutando
        return ResponseEntity.ok(job.resultado);
    }
}
