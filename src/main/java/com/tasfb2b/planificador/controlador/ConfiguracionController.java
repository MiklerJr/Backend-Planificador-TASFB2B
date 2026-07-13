package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.servicios.AltasEnCalienteService;
import com.tasfb2b.planificador.servicios.ConfiguracionCapacidadesService;
import com.tasfb2b.planificador.servicios.MotorGrafoCache;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/planificador/configuracion")
public class ConfiguracionController {

    private final ConfiguracionCapacidadesService capacidades;
    private final AltasEnCalienteService altasEnCaliente;
    private final RegistroJobs registroJobs;
    private final MotorGrafoCache motorCache;

    public ConfiguracionController(ConfiguracionCapacidadesService capacidades,
                                   AltasEnCalienteService altasEnCaliente,
                                   RegistroJobs registroJobs,
                                   MotorGrafoCache motorCache) {
        this.capacidades = capacidades;
        this.altasEnCaliente = altasEnCaliente;
        this.registroJobs = registroJobs;
        this.motorCache = motorCache;
    }

    @PutMapping("/aeropuertos/{icao}/capacidad")
    public ResponseEntity<Void> actualizarCapacidadAeropuerto(@PathVariable String icao, @RequestParam int valor) {
        return capacidades.actualizarCapacidadAeropuerto(icao, valor)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    @PutMapping("/vuelos/{idVuelo}/capacidad")
    public ResponseEntity<Void> actualizarCapacidadVuelo(@PathVariable String idVuelo, @RequestParam int valor) {
        return capacidades.actualizarCapacidadVuelo(idVuelo, valor)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/capacidades/restaurar")
    public ResponseEntity<Void> restaurarCapacidades() {
        capacidades.restaurarCapacidadesAFabrica();
        return ResponseEntity.ok().build();
    }

    @PutMapping("/vuelos/{idVuelo}/horario")
    public ResponseEntity<Map<String, Object>> actualizarHorarioVuelo(
            @PathVariable String idVuelo,
            @RequestParam(required = false) String salida,
            @RequestParam(required = false) String llegada) {
        try {
            String nuevoId = capacidades.actualizarHorarioVuelo(idVuelo, salida, llegada);
            return nuevoId != null
                    ? ResponseEntity.ok(Map.of("idVuelo", nuevoId, "aplicado", true))
                    : ResponseEntity.notFound().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(Map.of("aplicado", false, "motivo", ex.getMessage()));
        }
    }

    @PutMapping("/vuelos/{idVuelo}/destino")
    public ResponseEntity<Map<String, Object>> actualizarDestinoVuelo(
            @PathVariable String idVuelo,
            @RequestParam String valor,
            @RequestParam(required = false) String llegada) {
        try {
            String nuevoId = capacidades.actualizarDestinoVuelo(idVuelo, valor, llegada);
            return nuevoId != null
                    ? ResponseEntity.ok(Map.of("idVuelo", nuevoId, "aplicado", true))
                    : ResponseEntity.notFound().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(Map.of("aplicado", false, "motivo", ex.getMessage()));
        }
    }

    /**
     * Restaura el plan de vuelos de fábrica EN FRÍO: revierte horarios/destino de los vuelos
     * editados a sus valores originales Y elimina los vuelos agregados (efímeros, por
     * formulario o TXT). 200 {restaurados, eliminados} / 409 con simulación en curso.
     */
    @PostMapping("/vuelos/restaurar-horarios")
    public ResponseEntity<Map<String, Object>> restaurarHorariosVuelos() {
        try {
            int restaurados = capacidades.restaurarHorariosVuelosAFabrica();
            int eliminados = altasEnCaliente.eliminarVuelosEfimeros();
            return ResponseEntity.ok(Map.of("restaurados", restaurados, "eliminados", eliminados));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(Map.of(
                    "restaurados", 0, "eliminados", 0, "motivo", ex.getMessage()));
        }
    }

    /**
     * Carga masiva de planes de vuelo EN FRÍO desde TXT (mismo formato del dataset que la
     * carga en caliente del job: ORIG-DEST-HH:MM-HH:MM-CAPACIDAD, horas locales). Pensada
     * para la preparación de la prueba: los vuelos se aplican de inmediato a BD + catálogo
     * (efímeros) y entran al grafo al iniciar la próxima corrida. Duplicados y líneas
     * inválidas se descartan POR LÍNEA y se reportan, sin abortar el lote.
     * 200 {aplicados, descartados, detalleDescartados} / 400 sin archivos o sin ninguna
     * línea de vuelo / 409 con simulación en curso (usa la carga en caliente del job).
     */
    @PostMapping(value = "/vuelos/cargar-txt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> cargarVuelosTxtEnFrio(
            @RequestParam("archivos") MultipartFile[] archivos) {
        if (registroJobs.haySimulacionEnCurso()) {
            return ResponseEntity.status(409).body(Map.of(
                    "aplicados", 0,
                    "motivo", "hay una simulación en curso; usa la carga en caliente del job activo"));
        }
        if (archivos == null || archivos.length == 0)
            throw new ParametroInvalidoException("Se requiere al menos un archivo de vuelos.");

        int aplicados = 0;
        List<Map<String, Object>> descartados = new ArrayList<>();
        for (MultipartFile f : archivos) {
            if (f == null || f.isEmpty()) continue;
            List<AltasEnCalienteService.LineaVueloTxt> lineas;
            try (Reader r = new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8)) {
                lineas = AltasEnCalienteService.parsearAltasVueloTxt(r);
            } catch (IOException ex) {
                throw new ParametroInvalidoException(
                        "No se pudo leer " + f.getOriginalFilename() + ": " + ex.getMessage());
            }
            for (AltasEnCalienteService.LineaVueloTxt linea : lineas) {
                if (linea.motivoDescarte() != null) {
                    descartados.add(descarteDe(f.getOriginalFilename(), linea, linea.motivoDescarte()));
                    continue;
                }
                String motivo = altasEnCaliente.aplicarAltaVueloEnFrio(linea.alta());
                if (motivo == null) aplicados++;
                else descartados.add(descarteDe(f.getOriginalFilename(), linea, motivo));
            }
        }
        if (aplicados == 0 && descartados.isEmpty())
            throw new ParametroInvalidoException("Ninguna línea de vuelo en los archivos "
                    + "(formato esperado: ORIG-DEST-HH:MM-HH:MM-CAPACIDAD).");
        if (aplicados > 0) motorCache.invalidar();

        Map<String, Object> body = new HashMap<>();
        body.put("aplicados", aplicados);
        body.put("descartados", descartados.size());
        body.put("detalleDescartados", descartados);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> descarteDe(String archivo,
                                                  AltasEnCalienteService.LineaVueloTxt linea,
                                                  String motivo) {
        Map<String, Object> m = new HashMap<>();
        m.put("archivo", archivo);
        m.put("linea", linea.numeroLinea());
        m.put("contenido", linea.contenido());
        m.put("motivo", motivo);
        return m;
    }
}
