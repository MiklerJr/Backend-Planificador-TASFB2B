package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.jobs.AlertaColapso;
import com.tasfb2b.planificador.dto.almacenes.*;
import com.tasfb2b.planificador.dto.jobs.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.dto.vuelos.VuelosUsadosResponse;
import com.tasfb2b.planificador.services.jobs.JobQueryService;
import com.tasfb2b.planificador.services.jobs.JobState;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Lecturas y polling del estado de los jobs: listado de jobs, estado, estado-inicial (warm-up),
 * alerta de colapso, dashboard, indicadores, carga/uso de vuelos, ocupación/serie de almacenes,
 * asignaciones, bloques y resultado. Todos son read models que NO modifican el job. Rutas bajo
 * {@code /api/planificador}; CORS lo aporta el {@code CorsFilter} global.
 */
@RestController
@RequestMapping("/api/planificador")
public class JobQueryController {

    private final PlanificadorService service;
    // Los read models de telemetría (dashboard, indicadores, carga/uso de vuelos, ocupación de
    // almacenes, asignaciones) los sirve JobQueryService.
    // PlanificadorService queda para el ciclo de vida del job (estado, serie, bloques, resultado).
    private final JobQueryService jobQuery;

    public JobQueryController(PlanificadorService service, JobQueryService jobQuery) {
        this.service = service;
        this.jobQuery = jobQuery;
    }

    /**
     * Lista de jobs en memoria. Por defecto solo los activos (encolado,
     * calentando, ejecutando). Útil tras un refresh para reengancharse a una
     * simulación en marcha sin haber persistido el {@code jobId} en cliente.
     *
     * @param activos si true (default), filtra a estados activos. Si false,
     *                devuelve todos los jobs vivos en el registry.
     */
    @GetMapping("/jobs")
    public ResponseEntity<JobsListResponse> listarJobs(
            @RequestParam(defaultValue = "true") boolean activos) {
        return ResponseEntity.ok(service.listarJobsResponse(activos));
    }

    /**
     * Snapshot del ESTADO INICIAL de un job con warm-up: las asignaciones pre-calculadas cuyos
     * envíos siguen ACTIVOS al llegar a {@code fechaInicio} (en vuelo, en escala o con tramos
     * por salir), con tramos UTC completos. Con esto el mapa pinta los aviones que ya están en
     * el aire al inicio de la fase visible, usando la misma interpolación que con los bloques.
     * <ul>
     *   <li>404 — el job no existe.</li>
     *   <li>204 — aún no disponible (job encolado o calentando).</li>
     *   <li>200 — lista de asignaciones (vacía si el job no tuvo warm-up).</li>
     * </ul>
     */
    @GetMapping("/jobs/{jobId}/estado-inicial")
    public ResponseEntity<EstadoInicialResponse> estadoInicialJob(@PathVariable String jobId) {
        JobState job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.estadoInicial == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(service.buildEstadoInicialResponse(job));
    }

    /**
     * Serie temporal de ocupación de almacenes por SLOT de 60 min (eje UTC), una serie por bloque
     * publicado desde {@code desde}. Es la granularidad nativa del modelo interno: con ella el
     * front actualiza EN VIVO las maletas de cada almacén mientras su reloj de animación recorre
     * el bloque (la ocupación de un slot es el ACUMULADO vigente, incluida la espera en origen de
     * envíos sin ruta). Misma paginación que {@code /bloques}: {@code desde} = índice de bloque.
     */
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

    /**
     * Alerta de colapso logístico INMINENTE (pre-colapso) vigente del job: nivel VERDE/AMBAR/ROJO,
     * mensaje, bloque y los factores (utilización de almacén, holgura SLA del backlog). Solo
     * informa; el colapso real se refleja en el estado/métricas. 404 si el job no existe.
     */
    @GetMapping("/jobs/{jobId}/alerta-colapso")
    public ResponseEntity<AlertaColapso> alertaColapsoJob(@PathVariable String jobId) {
        JobState job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(job.alertaColapso != null ? job.alertaColapso : AlertaColapso.verde());
    }

    @GetMapping("/jobs/{jobId}/dashboard")
    public ResponseEntity<DashboardResponse> dashboardJob(@PathVariable String jobId) {
        DashboardResponse body = jobQuery.getDashboardJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/indicadores")
    public ResponseEntity<IndicadoresResponse> indicadoresJob(@PathVariable String jobId) {
        IndicadoresResponse body = jobQuery.getIndicadoresJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    /**
     * Carga de vuelos por bloque, PAGINADA (anti-OOM). El front empieza en {@code desde=0} y, mientras
     * la respuesta traiga {@code hayMas=true}, vuelve a pedir con {@code desde=proximoDesde}. {@code limit}
     * (filas por página) se clampea al tope del servidor; {@code limit<=0} usa el default de config.
     */
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

    /**
     * Ocupación de almacenes por bloque, PAGINADA (anti-OOM; mismo contrato de cursor que
     * {@code /vuelos/carga}). El front pagina con {@code desde}/{@code proximoDesde} mientras {@code hayMas}.
     */
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

    /**
     * Estado de UN envío por su {@code idEnvio} (consulta puntual del front). Pensado para cuando el
     * envío pertenece a un bloque anterior, ya purgado de la RAM del job: el detalle se reconstruye
     * desde la solución persistida en BD y se le añade el estado "en ruta".
     *
     * <p>Devuelve un {@link EnvioEstadoResponse}: la {@link AsignacionMaleta} (inicio =
     * {@code registroUtc}; aeropuertos = {@code tramos[].origen/destino}; vuelos = {@code rutaVuelos})
     * con cada {@code tramos[].estado} clasificado (COMPLETADO/EN_CURSO/PENDIENTE), más el estado
     * global del envío (PROGRAMADO/EN_VUELO/EN_ESCALA/ENTREGADO) y su ubicación.
     *
     * @param en instante UTC de referencia (ISO, p. ej. {@code 2026-01-03T14:30}). Si se omite, se
     *           usa el {@code horaFin} del último bloque publicado del job (el "ahora" de la simulación).
     *
     * <p>Responde 400 si {@code en} tiene formato inválido; 404 si el job no existe o si el envío no
     * tiene ruta activa persistida en este job (no existe, quedó en backlog/sin ruta, o la BD ya
     * refleja otra corrida).
     */
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

    /**
     * Bloques publicados de forma incremental por el job (escenarios 2 y 3).
     *
     * <p>El front pasa {@code desde} con el índice del próximo bloque que aún no
     * tiene; el backend devuelve los bloques disponibles a partir de ahí. Pensado
     * para polling cada pocos segundos durante el sleep {@code Sa - Ta}, de modo
     * que el front pueda dibujar vuelos y el estado de cada maleta a medida que
     * se procesan.
     *
     * <p>Respuesta:
     * <pre>
     * {
     *   "bloques":   [...],   // BloqueSimulacion[N..total]
     *   "total":     int,     // bloques publicados hasta ahora
     *   "terminado": boolean  // true si el job ya finalizó (estado != 'ejecutando')
     * }
     * </pre>
     */
    @GetMapping("/jobs/{jobId}/bloques")
    public ResponseEntity<Map<String, Object>> bloquesJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde) {
        JobState job = service.getJob(jobId);
        if (job == null) return ResponseEntity.notFound().build();

        Map<String, Object> body = new HashMap<>();
        body.put("bloques",   job.bloquesDesde(desde));
        body.put("total",     job.bloquesPublicados());
        // terminado = estado terminal alcanzado (completado, cancelado o error).
        body.put("terminado", !"encolado".equals(job.estado)
                           && !"calentando".equals(job.estado)
                           && !"ejecutando".equals(job.estado));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/resultado")
    public ResponseEntity<SimulacionResponse> resultadoJob(@PathVariable String jobId) {
        JobState job = service.getJob(jobId);
        if (job == null)             return ResponseEntity.notFound().build();
        if (job.resultado == null)   return ResponseEntity.noContent().build(); // 204 = aún ejecutando
        return ResponseEntity.ok(job.resultado);
    }
}
