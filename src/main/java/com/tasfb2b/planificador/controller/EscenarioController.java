package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.EjecucionParams;
import com.tasfb2b.planificador.dto.*;
import com.tasfb2b.planificador.exception.ParametroInvalidoException;
import com.tasfb2b.planificador.services.IngestaService;
import com.tasfb2b.planificador.services.JobState;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Arranque y control del ciclo de vida de los escenarios de simulación (Tanda 1D: extraído de
 * {@code PlanificadorController}): lanzamiento de E1/E2/E3, cancelación/reinicio de jobs,
 * cancelación de vuelos en vivo y los endpoints legacy síncronos ({@code /ejecutar},
 * {@code /ejecutar-colapso}, {@code /bloque/{index}}). Rutas inalteradas bajo
 * {@code /api/planificador}. CORS lo aporta el {@code CorsFilter} global.
 */
@RestController
@RequestMapping("/api/planificador")
public class EscenarioController {

    private final PlanificadorService service;
    private final PlanificadorProperties props;
    private final IngestaService ingesta;

    public EscenarioController(PlanificadorService service, PlanificadorProperties props,
                               IngestaService ingesta) {
        this.service = service;
        this.props = props;
        this.ingesta = ingesta;
    }

    /**
     * Fase 6B: rechaza arrancar una simulación mientras una ingesta está reemplazando el dataset
     * (la ingesta hace TRUNCATE + recarga el DataLoader que el motor usa). 409.
     */
    private void rechazarSiIngestaEnCurso() {
        if (ingesta.estaEnCurso()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hay una ingesta de dataset en curso; espera a que termine.");
        }
    }

    // ── Legacy síncronos ─────────────────────────────────────────────────────

    /**
     * Ejecuta la planificación de pedidos-rutas.
     *
     * @param algoritmo   "alns" (default) o "aco"
     * @param k           Factor de aceleración: K=1 día a día, K=14 sim-3días (default), K=144 colapso
     */
    @GetMapping("/ejecutar")
    public ResponseEntity<SimulacionResponse> ejecutar(
            @RequestParam(defaultValue = "alns") String algoritmo,
            @RequestParam(defaultValue = "14")   int    k) {

        rechazarSiIngestaEnCurso();
        return switch (algoritmo.toLowerCase()) {
            case "alns" -> ResponseEntity.ok(service.ejecutarALNS(k));
            default     -> throw new ParametroInvalidoException(
                    "algoritmo no soportado en este endpoint síncrono: '" + algoritmo + "' (use 'alns')");
        };
    }

    @GetMapping("/bloque/{index}")
    public ResponseEntity<BloqueSimulacion> getBloque(@PathVariable int index) {
        BloqueSimulacion bloque = service.getBloque(index);
        if (bloque == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(bloque);
    }

    // ── Escenario 3: hasta el colapso (legacy síncrono) ──────────────────────

    @GetMapping("/ejecutar-colapso")
    public ResponseEntity<SimulacionResponse> ejecutarColapso(
            @RequestParam(defaultValue = "75")   int    k,
            @RequestParam(defaultValue = "0.20") double umbralColapso) {

        rechazarSiIngestaEnCurso();
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        return ResponseEntity.ok(service.ejecutarHastaColapso(k, umbralColapso));
    }

    // ── Escenario 1: día a día ────────────────────────────────────────────────

    /**
     * Lanza el escenario 1 como job asíncrono. K se fija al default del yaml
     * (día a día). El front consume bloques vía
     * {@code GET /jobs/{jobId}/bloques?desde=N} igual que en E2/E3.
     *
     * <p>{@code fechaInicio} (opcional): si es posterior al inicio del dataset, el período
     * previo se PRE-CALCULA como warm-up — respeta el presupuesto Ta por bloque pero ignora
     * el sleep de Sa — y la fase visible arranca en fechaInicio respetando Sa. Mientras dura,
     * el job está en estado "calentando"; el snapshot de aviones aún en el aire queda en
     * {@code GET /jobs/{jobId}/estado-inicial}. 400 si fechaInicio está fuera del dataset.
     */
    @PostMapping("/escenario1/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarEsc1Async(
            @RequestParam(defaultValue = "alns") String algoritmo,
            @RequestParam(required = false)      Long   seed,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fechaInicio) {
        rechazarSiIngestaEnCurso();
        String error = service.validarParametrosEscenario(null, null, null, fechaInicio);
        if (error != null) throw new ParametroInvalidoException(error);

        JobState job = service.iniciarEscenario1Async(algoritmo, seed, fechaInicio);
        Map<String, Object> body = new HashMap<>();
        body.put("jobId",     job.getJobId());
        body.put("escenario", "1");
        body.put("algoritmo", job.algoritmo);
        body.put("k",         job.getK());
        body.put("seed",      job.seed);
        body.put("estado",    job.estado);
        if (job.fechaInicio != null) body.put("fechaInicio", job.fechaInicio.toString());
        return ResponseEntity.accepted().body(body);
    }

    // ── Escenarios 2/3 asíncronos ────────────────────────────────────────────
    // Soportan ejecuciones largas (30-90 min con sleep activo) sin bloquear el HTTP.

    /**
     * Lanza el escenario 2. Todos los parámetros son opcionales — los que falten caen al
     * default del yaml. Permite override por petición de {@code Sa}, {@code Ta} y {@code dias}
     * para que cada job pueda ejecutar con su propia ventana sin tocar configuración global.
     *
     * <p><b>K es FIJO en el escenario 2 (regla de negocio: 144)</b>: el parámetro {@code k}
     * se acepta solo por compatibilidad/verificación — si llega con un valor distinto al fijo
     * se responde 400; el motor usa siempre el K del yaml.
     *
     * <p>Ejemplo: {@code /escenario2/iniciar?sa=5&dias=5&algoritmo=alns}
     * → cálculo dinámico {@code ventanas = (5·24·60)/5 = 1440} bloques de Sc=K·Sa.
     */
    @PostMapping("/escenario2/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarEsc2(
            @RequestParam(required = false)       Integer k,
            @RequestParam(defaultValue = "alns")  String algoritmo,
            @RequestParam(required = false)        Long  seed,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fechaInicio,
            @RequestParam(required = false)        Integer sa,
            @RequestParam(required = false)        Integer ta,
            @RequestParam(required = false)        Integer dias,
            @RequestParam(defaultValue = "false")  boolean procesamientoPrevio) {

        rechazarSiIngestaEnCurso();
        int kFijo = props.getScenario().getKDefault2();
        if (k != null && k != kFijo) {
            throw new ParametroInvalidoException(
                    "k es fijo en el escenario 2: " + kFijo + " (recibido: " + k + ")");
        }
        String error = service.validarParametrosEscenario(null, sa, ta, fechaInicio);
        if (error != null) throw new ParametroInvalidoException(error);

        EjecucionParams params = new EjecucionParams();
        // K no se propaga del request: iniciarEscenario2Async fija siempre el del yaml.
        params.setMotor(algoritmo);
        params.setSeed(seed);
        params.setFechaInicio(fechaInicio);
        params.setSaMin(sa);
        params.setTaSegundos(ta);
        params.setDias(dias);
        // Warm-up (procesamiento previo) DESACTIVADO: se ignora el flag entrante y se fuerza a
        // false para que el período previo a fechaInicio nunca se simule. El @RequestParam se
        // mantiene por compatibilidad con el front, pero no tiene efecto. Revertir: volver a
        // setProcesamientoPrevio(procesamientoPrevio).
        params.setProcesamientoPrevio(false);

        JobState job = service.iniciarEscenario2Async(params);
        Map<String, Object> body = new HashMap<>();
        body.put("jobId",     job.getJobId());
        body.put("escenario", "2");
        body.put("algoritmo", job.algoritmo);
        body.put("k",         kFijo);
        body.put("seed",      job.seed);
        body.put("estado",    job.estado);
        if (sa != null)   body.put("sa", sa);
        if (ta != null)   body.put("ta", ta);
        if (dias != null) body.put("dias", dias);
        body.put("procesamientoPrevio", false);   // forzado OFF: el warm-up está desactivado
        if (job.fechaInicio != null) body.put("fechaInicio", job.fechaInicio.toString());
        return ResponseEntity.accepted().body(body);
    }

    /**
     * Lanza el escenario 3 (hasta colapso). {@code fechaInicio} (opcional): igual que en E1 —
     * warm-up Ta-only hasta esa fecha (estado "calentando", snapshot en
     * {@code /jobs/{id}/estado-inicial}) y la vigilancia del colapso arranca desde fechaInicio.
     *
     * <p><b>K es FIJO en el escenario 3 (regla de negocio: 144)</b>: {@code k} se acepta solo
     * por compatibilidad/verificación — 400 si llega distinto al fijo.
     * 400 también si fechaInicio está fuera del dataset.
     */
    @PostMapping("/escenario3/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarEsc3(
            @RequestParam(required = false)       Integer k,
            @RequestParam(defaultValue = "0.20")  double umbralColapso,
            @RequestParam(defaultValue = "alns")  String algoritmo,
            @RequestParam(required = false)        Long  seed,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fechaInicio) {
        rechazarSiIngestaEnCurso();
        int kFijo = props.getScenario().getKDefault3();
        if (k != null && k != kFijo) {
            throw new ParametroInvalidoException(
                    "k es fijo en el escenario 3: " + kFijo + " (recibido: " + k + ")");
        }
        String error = service.validarParametrosEscenario(null, null, null, fechaInicio);
        if (error != null) throw new ParametroInvalidoException(error);

        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        JobState job = service.iniciarEscenario3Async(umbralColapso, algoritmo, seed, fechaInicio);
        return ResponseEntity.accepted().body(Map.of(
                "jobId",         job.getJobId(),
                "escenario",     "3",
                "algoritmo",     job.algoritmo,
                "k",             kFijo,
                "seed",          job.seed,
                "umbralColapso", umbralColapso,
                "estado",        job.estado
        ));
    }

    // ── Control de jobs en vivo ──────────────────────────────────────────────

    @PostMapping("/jobs/{jobId}/cancelar")
    public ResponseEntity<Map<String, Object>> cancelarJob(@PathVariable String jobId) {
        boolean ok = service.cancelarJob(jobId);
        return ResponseEntity.ok(Map.of("jobId", jobId, "cancelado", ok));
    }

    /**
     * Reinicia un job (botón "reinicio" del front): detiene la simulación en curso y lanza una
     * NUEVA con los MISMOS parámetros de la ejecución anterior (misma seed ⇒ re-juego idéntico).
     * Crea un jobId NUEVO; el front debe reengancharse a ese id. Funciona en E1/E2/E3 y tanto si
     * el job estaba activo como si ya había terminado.
     *
     * @return 202 con {@code jobIdAnterior} y el {@code jobId} nuevo; 404 si el job no existe;
     *         400 si el escenario no es reiniciable.
     */
    @PostMapping("/jobs/{jobId}/reiniciar")
    public ResponseEntity<Map<String, Object>> reiniciarJob(@PathVariable String jobId) {
        JobState viejo = service.getJob(jobId);
        if (viejo == null) return ResponseEntity.notFound().build();

        JobState nuevo = service.reiniciarJob(jobId);
        if (nuevo == null) {
            throw new ParametroInvalidoException("escenario no reiniciable: " + viejo.getEscenario());
        }
        return ResponseEntity.accepted().body(Map.of(
                "jobIdAnterior", jobId,
                "jobId",         nuevo.getJobId(),
                "escenario",     nuevo.getEscenario(),
                "algoritmo",     nuevo.algoritmo,
                "seed",          nuevo.seed,
                "estado",        nuevo.estado));
    }

    /**
     * Cancela un vuelo concreto EN VIVO durante un job async (E1 async / E2 / E3). El vuelo queda no
     * disponible solo el día de {@code fechaHoraSalida}; los envíos ya programados en él se devuelven
     * al backlog y se re-enrutan en los bloques siguientes. El vuelo se identifica por
     * {@code origen} + {@code destino} + {@code fechaHoraSalida} (los mismos datos de
     * {@code /jobs/{jobId}/vuelos/usados}).
     *
     * @return 202 si se encoló, 404 si el job no existe, 409 si el job ya terminó.
     */
    @PostMapping("/jobs/{jobId}/cancelar-vuelo")
    public ResponseEntity<Map<String, Object>> cancelarVueloJob(
            @PathVariable String jobId,
            @RequestBody CancelacionVueloRequest orden) {
        if (service.getJob(jobId) == null) return ResponseEntity.notFound().build();
        boolean ok = service.solicitarCancelacionVuelo(jobId, orden);
        if (!ok) {
            return ResponseEntity.status(409).body(Map.of(
                    "jobId", jobId, "encolado", false,
                    "motivo", "el job no está activo (ya terminó o fue cancelado)"));
        }
        return ResponseEntity.accepted().body(Map.of(
                "jobId",    jobId,
                "encolado", true,
                "origen",   orden.getOrigen(),
                "destino",  orden.getDestino(),
                "fechaHoraSalida", String.valueOf(orden.getFechaHoraSalida())));
    }
}
