package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.dto.vuelos.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.simulacion.EjecucionParametros;
import com.tasfb2b.planificador.dto.jobs.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.servicios.ingesta.IngestaService;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.ingesta.MigradorEnviosDb;
import com.tasfb2b.planificador.servicios.PlanificadorService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private void rechazarSiIngestaEnCurso() {
        if (ingesta.estaEnCurso()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hay una ingesta de dataset en curso; espera a que termine.");
        }
    }

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

    @GetMapping("/ejecutar-colapso")
    public ResponseEntity<SimulacionResponse> ejecutarColapso(
            @RequestParam(defaultValue = "75")   int    k,
            @RequestParam(defaultValue = "0.20") double umbralColapso) {

        rechazarSiIngestaEnCurso();
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        return ResponseEntity.ok(service.ejecutarHastaColapso(k, umbralColapso));
    }

    @PostMapping("/escenario1/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarEsc1Async(
            @RequestParam(defaultValue = "alns") String algoritmo,
            @RequestParam(required = false)      Long   seed,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fechaInicio,
            @RequestParam(defaultValue = "false") boolean enVivo) {
        rechazarSiIngestaEnCurso();
        // En operación EN VIVO el cursor es now() UTC: fechaInicio no aplica (no se valida vs dataset).
        if (!enVivo) {
            String error = service.validarParametrosEscenario(null, null, null, fechaInicio);
            if (error != null) throw new ParametroInvalidoException(error);
        }

        EstadoJob job = service.iniciarEscenario1Async(algoritmo, seed, fechaInicio, enVivo);
        Map<String, Object> body = new HashMap<>();
        body.put("jobId",     job.getJobId());
        body.put("escenario", "1");
        body.put("algoritmo", job.algoritmo);
        body.put("k",         job.getK());
        body.put("seed",      job.seed);
        body.put("estado",    job.estado);
        body.put("enVivo",    job.enVivo);
        if (job.fechaInicio != null) body.put("fechaInicio", job.fechaInicio.toString());
        return ResponseEntity.accepted().body(body);
    }

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

        EjecucionParametros params = new EjecucionParametros();
        // K no se propaga del request: iniciarEscenario2Async fija siempre el del yaml.
        params.setMotor(algoritmo);
        params.setSeed(seed);
        params.setFechaInicio(fechaInicio);
        params.setSaMin(sa);
        params.setTaSegundos(ta);
        params.setDias(dias);
        params.setProcesamientoPrevio(false);

        EstadoJob job = service.iniciarEscenario2Async(params);
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
        EstadoJob job = service.iniciarEscenario3Async(umbralColapso, algoritmo, seed, fechaInicio);
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


    @PostMapping("/jobs/{jobId}/cancelar")
    public ResponseEntity<Map<String, Object>> cancelarJob(@PathVariable String jobId) {
        boolean ok = service.cancelarJob(jobId);
        return ResponseEntity.ok(Map.of("jobId", jobId, "cancelado", ok));
    }

    @PostMapping("/jobs/{jobId}/reiniciar")
    public ResponseEntity<Map<String, Object>> reiniciarJob(@PathVariable String jobId) {
        EstadoJob viejo = service.getJob(jobId);
        if (viejo == null) return ResponseEntity.notFound().build();

        EstadoJob nuevo = service.reiniciarJob(jobId);
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

    @PostMapping({"/jobs/{jobId}/inyectar-envios", "/jobs/{jobId}/registrar-envios"})
    public ResponseEntity<Map<String, Object>> inyectarEnvios(
            @PathVariable String jobId,
            @RequestBody InyeccionEnviosRequest req) {
        if (service.getJob(jobId) == null) return ResponseEntity.notFound().build();
        int encolados = service.solicitarInyeccionEnvios(jobId, req);   // -1 = job inactivo; lanza 400
        if (encolados < 0) {
            return ResponseEntity.status(409).body(Map.of(
                    "jobId", jobId, "encolado", false,
                    "motivo", "el job no está activo (ya terminó o fue cancelado)"));
        }
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId, "encolado", true, "encolados", encolados));
    }

    @PostMapping(value = "/jobs/{jobId}/cargar-envios-txt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> cargarEnviosTxt(
            @PathVariable String jobId,
            @RequestParam("archivos") MultipartFile[] archivos,
            @RequestParam(required = false) String origen,
            @RequestParam(required = false) String registrador,
            @RequestParam(required = false) String sede) {
        if (service.getJob(jobId) == null) return ResponseEntity.notFound().build();
        if (archivos == null || archivos.length == 0)
            throw new ParametroInvalidoException("Se requiere al menos un archivo de envíos.");

        List<InyeccionEnviosRequest.Item> items = new ArrayList<>();
        for (MultipartFile f : archivos) {
            if (f == null || f.isEmpty()) continue;
            String icao = (origen != null && !origen.isBlank())
                    ? origen.trim()
                    : MigradorEnviosDb.origenIcaoDeNombre(f.getOriginalFilename());
            if (icao == null)
                throw new ParametroInvalidoException(
                        "Archivo sin ICAO de origen derivable del nombre: " + f.getOriginalFilename()
                      + " (use _envios_<ICAO>_.txt o el parámetro 'origen').");
            try (Reader r = new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8)) {
                items.addAll(MigradorEnviosDb.parsearEnviosParaInyeccion(r, icao, registrador, sede));
            } catch (IOException ex) {
                throw new ParametroInvalidoException(
                        "No se pudo leer " + f.getOriginalFilename() + ": " + ex.getMessage());
            }
        }
        if (items.isEmpty())
            throw new ParametroInvalidoException("Ningún envío válido en los archivos "
                    + "(formato esperado: id-YYYYMMDD-HH-MM-DESTINO-cantidad-idCliente).");

        InyeccionEnviosRequest req = new InyeccionEnviosRequest();
        req.setEnvios(items);
        int encolados = service.solicitarInyeccionEnvios(jobId, req);   // -1 = job inactivo; lanza 400
        if (encolados < 0) {
            return ResponseEntity.status(409).body(Map.of(
                    "jobId", jobId, "encolado", false,
                    "motivo", "el job no está activo (ya terminó o fue cancelado)"));
        }
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId, "encolado", true, "encolados", encolados));
    }
}
