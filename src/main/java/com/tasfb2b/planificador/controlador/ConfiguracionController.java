package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.servicios.ConfiguracionCapacidadesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
