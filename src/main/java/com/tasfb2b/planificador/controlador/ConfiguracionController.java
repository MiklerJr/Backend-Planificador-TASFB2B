package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.servicios.ConfiguracionCapacidadesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/planificador/configuracion")
public class ConfiguracionController {

    private final ConfiguracionCapacidadesService capacidades;

    public ConfiguracionController(ConfiguracionCapacidadesService capacidades) {
        this.capacidades = capacidades;
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

    @PostMapping("/vuelos/restaurar-horarios")
    public ResponseEntity<Map<String, Object>> restaurarHorariosVuelos() {
        try {
            int restaurados = capacidades.restaurarHorariosVuelosAFabrica();
            return ResponseEntity.ok(Map.of("restaurados", restaurados));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(Map.of("restaurados", 0, "motivo", ex.getMessage()));
        }
    }
}
