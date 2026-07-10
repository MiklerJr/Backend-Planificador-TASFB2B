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

    /** PUT .../aeropuertos/{icao}/capacidad?valor=1500 → 200 / 400 (valor<1) / 404 (icao inexistente).
     *  El modo (frío/caliente) se decide automáticamente según si hay una simulación en curso. */
    @PutMapping("/aeropuertos/{icao}/capacidad")
    public ResponseEntity<Void> actualizarCapacidadAeropuerto(@PathVariable String icao, @RequestParam int valor) {
        return capacidades.actualizarCapacidadAeropuerto(icao, valor)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    /** PUT .../vuelos/{idVuelo}/capacidad?valor=450 → 200 / 400 (valor<1) / 404 (id inexistente).
     *  idVuelo puede venir como "SKBO-SEQM-08:30" (con o sin los dos puntos). */
    @PutMapping("/vuelos/{idVuelo}/capacidad")
    public ResponseEntity<Void> actualizarCapacidadVuelo(@PathVariable String idVuelo, @RequestParam int valor) {
        return capacidades.actualizarCapacidadVuelo(idVuelo, valor)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    /** POST .../capacidades/restaurar → 200. Devuelve TODAS las capacidades (aeropuertos y vuelos) a su
     *  valor original de fábrica en BD + RAM + grafo, con efecto inmediato en el job en curso si lo hay. */
    @PostMapping("/capacidades/restaurar")
    public ResponseEntity<Void> restaurarCapacidades() {
        capacidades.restaurarCapacidadesAFabrica();
        return ResponseEntity.ok().build();
    }

    /** PUT .../vuelos/{idVuelo}/horario?salida=HH:mm&llegada=HH:mm (horas LOCALES; al menos una) →
     *  200 / 400 (hora malformada o sin parámetros) / 404 (id inexistente) / 409 (simulación en curso:
     *  el horario solo se modifica EN FRÍO; en caliente usar cancelar-vuelo + agregar-vuelo).
     *  Persistente hasta restaurar-horarios. El idVuelo se RENOMBRA si cambia la salida
     *  (el invariante es id_vuelo ≡ ORIGEN-DESTINO-HHMM de la salida vigente): la respuesta trae
     *  {@code idVuelo} con el id resultante para que el front lo use. Acepta el id con o sin los dos puntos. */
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

    /** POST .../vuelos/restaurar-horarios → 200 {restaurados:N} / 409 (simulación en curso). Devuelve los
     *  horarios de TODOS los vuelos a su valor original de fábrica (BD + RAM) e invalida el grafo. */
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
