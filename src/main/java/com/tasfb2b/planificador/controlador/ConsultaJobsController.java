package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.dto.jobs.AlertaColapso;
import com.tasfb2b.planificador.dto.almacenes.*;
import com.tasfb2b.planificador.dto.jobs.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.dto.vuelos.VuelosUsadosResponse;
import com.tasfb2b.planificador.servicios.jobs.ConsultaJobsService;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
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
public class ConsultaJobsController {

    private final PlanificadorService service;
    private final ConsultaJobsService jobQuery;

    public ConsultaJobsController(PlanificadorService service, ConsultaJobsService jobQuery) {
        this.service = service;
        this.jobQuery = jobQuery;
    }

    @GetMapping("/jobs")
    public ResponseEntity<ListaJobsResponse> listarJobs(
            @RequestParam(defaultValue = "true") boolean activos) {
        return ResponseEntity.ok(service.listarJobsResponse(activos));
    }

    @GetMapping("/jobs/activo")
    public ResponseEntity<JobActivoResponse> jobActivo() {
        return ResponseEntity.ok(jobQuery.getJobActivo());
    }

    @GetMapping("/jobs/{jobId}/estado-inicial")
    public ResponseEntity<EstadoInicialResponse> estadoInicialJob(@PathVariable String jobId) {
        EstadoJob job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.estadoInicial == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(service.construirEstadoInicialResponse(job));
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
    public ResponseEntity<EstadoJobResponse> estadoJob(@PathVariable String jobId) {
        EstadoJobResponse body = service.getEstadoJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/alerta-colapso")
    public ResponseEntity<AlertaColapso> alertaColapsoJob(@PathVariable String jobId) {
        EstadoJob job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(job.alertaColapso != null ? job.alertaColapso : AlertaColapso.verde());
    }

    @GetMapping("/jobs/{jobId}/dashboard")
    public ResponseEntity<TableroResponse> tableroJob(@PathVariable String jobId) {
        TableroResponse body = jobQuery.getTableroJob(jobId);
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
        if (service.getJob(jobId) == null) return ResponseEntity.notFound().build();
        java.time.LocalDateTime instante;
        try {
            instante = (en == null || en.isBlank()) ? null : java.time.LocalDateTime.parse(en);
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }
        EnvioEstadoResponse estado = service.buscarEstadoEnvio(jobId, idEnvio, instante);
        if (estado == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(estado);
    }

    @GetMapping("/jobs/{jobId}/bloques")
    public ResponseEntity<Map<String, Object>> bloquesJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde) {
        EstadoJob job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        Map<String, Object> body = new HashMap<>();
        body.put("bloques",   job.bloquesDesdeExacto(desde));
        body.put("total",     job.bloquesPublicados());
        body.put("primerBloqueDisponible", job.primerBloqueDisponible());
        Long duracionRealMs = job.getDuracionRealMs();
        if (duracionRealMs != null) body.put("duracionRealMs", duracionRealMs);
        body.put("terminado", !"encolado".equals(job.estado)
                           && !"calentando".equals(job.estado)
                           && !"ejecutando".equals(job.estado));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/resultado")
    public ResponseEntity<SimulacionResponse> resultadoJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "true") boolean incluirVuelosPlaneados) {
        EstadoJob job = service.getJob(jobId);
        if (job == null)             return ResponseEntity.notFound().build();
        if (job.resultado == null)   return ResponseEntity.noContent().build();
        SimulacionResponse body = incluirVuelosPlaneados
                ? job.resultado
                : sinVuelosPlaneados(job.resultado);
        return ResponseEntity.ok(body);
    }

    private static SimulacionResponse sinVuelosPlaneados(SimulacionResponse full) {
        SimulacionResponse copia = new SimulacionResponse();
        copia.setMetricas(full.getMetricas());
        copia.setTotalBloques(full.getTotalBloques());
        copia.setAeropuertosInfo(full.getAeropuertosInfo());
        copia.setK(full.getK());
        copia.setSaMinutos(full.getSaMinutos());
        return copia;
    }
}
