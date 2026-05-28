package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.EjecucionParams;
import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.dto.VuelosUsadosResponse;
import com.tasfb2b.planificador.services.JobState;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
@RestController
@RequestMapping("/api/planificador")
public class PlanificadorController {

    private final PlanificadorService service;
    private final PlanificadorProperties props;

    public PlanificadorController(PlanificadorService service, PlanificadorProperties props) {
        this.service = service;
        this.props = props;
    }

    /**
     * Metadatos del dataset cargado (rango temporal disponible, conteos).
     * Permite al front validar {@code fechaInicio} antes de enviar el job:
     * si la fecha está fuera de {@code [primeraVentana, ultimaVentana]} el
     * backend la ignora silenciosamente.
     */
    @GetMapping("/dataset/info")
    public ResponseEntity<Map<String, Object>> datasetInfo() {
        return ResponseEntity.ok(service.getDatasetInfo());
    }

    /**
     * Mapa estático de aeropuertos del dataset cargado:
     * {@code {[codigo]: {codigo, latitud, longitud, capacidadAlmacen}}}. Pensado para que el
     * front lo cachee al cargar la app y dibuje bloques incrementalmente
     * sin esperar a {@code /resultado}. No cambia en runtime.
     */
    @GetMapping("/aeropuertos")
    public ResponseEntity<Map<String, SimulacionResponse.AeropuertoDTO>> aeropuertos() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(service.getAeropuertosInfo());
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
    public ResponseEntity<Map<String, Object>> listarJobs(
            @RequestParam(defaultValue = "true") boolean activos) {
        List<JobState> lista = activos ? service.listarJobsActivos() : service.listarTodosLosJobs();
        List<Map<String, Object>> items = new ArrayList<>(lista.size());
        for (JobState j : lista) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("jobId", j.getJobId());
            item.put("escenario", j.getEscenario());
            item.put("algoritmo", j.algoritmo);
            item.put("estado", j.estado);
            item.put("k", j.getK());
            item.put("seed", j.seed);
            if (j.fechaInicio != null) item.put("fechaInicio", j.fechaInicio.toString());
            item.put("inicio", j.inicio.toString());
            if (j.fin != null) item.put("fin", j.fin.toString());
            item.put("progreso", j.getProgreso());
            item.put("progresoWarmup", j.getProgresoWarmup());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobs", items);
        body.put("total", items.size());
        return ResponseEntity.ok(body);
    }

    /**
     * Catálogo de escenarios disponibles para el front. Devuelve los valores
     * por defecto (Sa, Ta, K, umbrales) que toma el backend desde
     * {@link PlanificadorProperties.Scenario}, junto con una descripción
     * human-readable y la lista de motores soportados.
     *
     * <p>Pensado para que el front no tenga que hardcodear los defaults ni los
     * textos de los escenarios.
     */
    @GetMapping("/escenarios")
    public ResponseEntity<Map<String, Object>> catalogoEscenarios() {
        PlanificadorProperties.Scenario sc = props.getScenario();

        Map<String, Object> esc1 = new HashMap<>();
        esc1.put("id", 1);
        esc1.put("nombre", "Día a día (tiempo real)");
        esc1.put("descripcion",
                "Planificación viva: cada corrida cubre un único bloque Sa. " +
                "El wall-clock por bloque es Sa real, sin aceleración.");
        esc1.put("kDefault", sc.getKDefault1());
        esc1.put("simulaTiempoReal", sc.isSimularTiempoReal1());
        esc1.put("endpoints", Map.of(
                "iniciar",     "POST /api/planificador/escenario1/iniciar",
                "inicializar", "POST /api/planificador/escenario1/inicializar",
                "ventana",     "GET  /api/planificador/escenario1/ventana",
                "estado",      "GET  /api/planificador/escenario1/estado",
                "bloque",      "GET  /api/planificador/escenario1/bloque/{index}"
        ));

        Map<String, Object> esc2 = new HashMap<>();
        esc2.put("id", 2);
        esc2.put("nombre", "Período (3/5/7 días)");
        esc2.put("descripcion",
                "Replays/simulaciones de un período cerrado. Entre bloques duerme " +
                "(Sa - Ta) cuando simularTiempoReal2=true, para imitar el ritmo real.");
        esc2.put("kDefault", sc.getKDefault2());
        esc2.put("simulaTiempoReal", sc.isSimularTiempoReal2());
        esc2.put("endpoints", Map.of(
                "iniciar", "POST /api/planificador/escenario2/iniciar"
        ));

        Map<String, Object> esc3 = new HashMap<>();
        esc3.put("id", 3);
        esc3.put("nombre", "Hasta colapso");
        esc3.put("descripcion",
                "Estrés / capacity planning. Avanza lo más rápido posible (a menos " +
                "que simularTiempoReal3=true) hasta que se dispara la condición de colapso.");
        esc3.put("kDefault", sc.getKDefault3());
        esc3.put("simulaTiempoReal", sc.isSimularTiempoReal3());
        esc3.put("umbralColapso", sc.getUmbralColapso());
        esc3.put("umbralColapsoBacklog", sc.getUmbralColapsoBacklog());
        esc3.put("endpoints", Map.of(
                "iniciar", "POST /api/planificador/escenario3/iniciar"
        ));

        Map<String, Object> body = new HashMap<>();
        body.put("saMinutos", sc.getSaMinutos());
        body.put("taSegundos", sc.getTaSegundos());
        body.put("motoresSoportados", java.util.List.of(
                PlanificadorService.MOTOR_ALNS,
                PlanificadorService.MOTOR_ACO
        ));
        body.put("escenarios", java.util.List.of(esc1, esc2, esc3));
        return ResponseEntity.ok(body);
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

    /**
     * Lanza el escenario 1 como job asíncrono. K se fija al default del yaml
     * (día a día). El front consume bloques vía
     * {@code GET /jobs/{jobId}/bloques?desde=N} igual que en E2/E3.
     */
    @PostMapping("/escenario1/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarEsc1Async(
            @RequestParam(defaultValue = "0.0")  double cancelProb,
            @RequestParam(defaultValue = "alns") String algoritmo,
            @RequestParam(required = false)      Long   seed) {
        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb));
        JobState job = service.iniciarEscenario1Async(cancelProb, algoritmo, seed);
        return ResponseEntity.accepted().body(Map.of(
                "jobId",     job.getJobId(),
                "escenario", "1",
                "algoritmo", job.algoritmo,
                "k",         job.getK(),
                "seed",      job.seed,
                "estado",    job.estado
        ));
    }

    @PostMapping("/escenario1/inicializar")
    public ResponseEntity<Map<String, Object>> inicializarEsc1(
            @RequestParam(defaultValue = "0.0")  double cancelProb,
            @RequestParam(defaultValue = "alns") String algoritmo,
            @RequestParam(required = false)      Long   seed) {

        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb));
        return ResponseEntity.ok(service.inicializarEscenario1(cancelProb, algoritmo, seed));
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

    /**
     * Lanza el escenario 2. Todos los parámetros excepto {@code k} son opcionales —
     * los que falten caen al default del yaml. Permite override por petición de
     * {@code Sa}, {@code Ta} y {@code dias} para que cada job pueda ejecutar con
     * su propia ventana sin tocar configuración global.
     *
     * <p>Ejemplo: {@code /escenario2/iniciar?k=120&sa=5&dias=5&algoritmo=alns}
     * → cálculo dinámico {@code ventanas = (5·24·60)/5 = 1440} bloques de Sc=K·Sa.
     */
    @PostMapping("/escenario2/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarEsc2(
            @RequestParam(defaultValue = "14")    int    k,
            @RequestParam(defaultValue = "0.0")   double cancelProb,
            @RequestParam(defaultValue = "alns")  String algoritmo,
            @RequestParam(required = false)        Long  seed,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fechaInicio,
            @RequestParam(required = false)        Integer sa,
            @RequestParam(required = false)        Integer ta,
            @RequestParam(required = false)        Integer dias) {
        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb));

        EjecucionParams params = new EjecucionParams();
        params.setK(k);
        params.setCancelProb(cancelProb);
        params.setMotor(algoritmo);
        params.setSeed(seed);
        params.setFechaInicio(fechaInicio);
        params.setSaMin(sa);
        params.setTaSegundos(ta);
        params.setDias(dias);

        JobState job = service.iniciarEscenario2Async(params);
        Map<String, Object> body = new HashMap<>();
        body.put("jobId",     job.getJobId());
        body.put("escenario", "2");
        body.put("algoritmo", job.algoritmo);
        body.put("k",         k);
        body.put("seed",      job.seed);
        body.put("estado",    job.estado);
        if (sa != null)   body.put("sa", sa);
        if (ta != null)   body.put("ta", ta);
        if (dias != null) body.put("dias", dias);
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
        // Warm-up: si fechaInicio obliga a simular hasta esa fecha antes de
        // publicar, el front consulta estos campos mientras estado="calentando".
        body.put("bloqueWarmup",       job.bloqueWarmup);
        body.put("totalBloquesWarmup", job.totalBloquesWarmup);
        body.put("progresoWarmup",     job.getProgresoWarmup());
        // Cola: si estado="encolado", posicionEnCola indica el turno (1-based).
        // Es 0 cuando ya está corriendo o terminó.
        body.put("posicionEnCola",     service.posicionEnCola(jobId));
        // Cancelación: true si el usuario llamó a /cancelar. Permite al front
        // distinguir cancelación voluntaria de fallo real sin parsear `error`.
        body.put("canceladoPorUsuario", job.canceladoPorUsuario);
        body.put("taPromedioMs",  job.taPromedioMs);
        body.put("inicio",        job.inicio.toString());
        if (job.fin != null) body.put("fin", job.fin.toString());
        if (job.error != null) body.put("error", job.error);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/dashboard")
    public ResponseEntity<Map<String, Object>> dashboardJob(@PathVariable String jobId) {
        Map<String, Object> body = service.getDashboardJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/indicadores")
    public ResponseEntity<Map<String, Object>> indicadoresJob(@PathVariable String jobId) {
        Map<String, Object> body = service.getIndicadoresJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/vuelos/carga")
    public ResponseEntity<Map<String, Object>> cargaVuelosJob(@PathVariable String jobId) {
        Map<String, Object> body = service.getCargaVuelosJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/vuelos/usados")
    public ResponseEntity<VuelosUsadosResponse> vuelosUsadosJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde) {
        VuelosUsadosResponse body = service.getVuelosUsadosJob(jobId, desde);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/almacenes/ocupacion")
    public ResponseEntity<Map<String, Object>> ocupacionAlmacenesJob(@PathVariable String jobId) {
        Map<String, Object> body = service.getOcupacionAlmacenesJob(jobId);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs/{jobId}/asignaciones")
    public ResponseEntity<Map<String, Object>> asignacionesJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int desde,
            @RequestParam(required = false) String aeropuerto,
            @RequestParam(required = false) String vueloId,
            @RequestParam(defaultValue = "false") boolean soloEnrutadas) {
        Map<String, Object> body = service.getAsignacionesJob(jobId, desde, aeropuerto, vueloId, soloEnrutadas);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/demanda/resumen")
    public ResponseEntity<Map<String, Object>> demandaResumen(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta,
            @RequestParam(defaultValue = "20") int top) {
        return ResponseEntity.ok(service.getDemandaResumen(desde, hasta, top));
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
     * Descarga el CSV de auditoría de un job completado. 25 columnas con
     * validación formal por envío de las restricciones del cliente
     * (cumpleSLA, sinCiclos, escalaMinOK, capacidad, almacén, score 0-100) y
     * los timestamps ISO de inicio (readyTime) y fin del envío (llegada
     * + DEST_STORAGE_MIN).
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
