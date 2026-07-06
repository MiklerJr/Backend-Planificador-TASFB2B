package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.utilidades.CargadorDatos;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/planificador/configuracion")
public class ConfiguracionController {

    private final JdbcTemplate jdbcTemplate;
    private final CargadorDatos cargadorDatos;

    public ConfiguracionController(JdbcTemplate jdbcTemplate, CargadorDatos cargadorDatos) {
        this.jdbcTemplate = jdbcTemplate;
        this.cargadorDatos = cargadorDatos;
    }

    @PutMapping("/aeropuertos/{icao}/capacidad")
    public ResponseEntity<Void> actualizarCapacidadAeropuerto(@PathVariable String icao, @RequestParam int valor) {
        int rows = jdbcTemplate.update("UPDATE aeropuerto SET capacidad_almacen = ? WHERE icao = ?", valor, icao);
        if (rows > 0) {
            cargadorDatos.actualizarCapacidadAeropuerto(icao, valor);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PutMapping("/vuelos/{idVuelo}/capacidad")
    public ResponseEntity<Void> actualizarCapacidadVuelo(@PathVariable String idVuelo, @RequestParam int valor) {
        // idVuelo desde el frontend suele venir como "SKBO-SEQM-08:30"
        String idDb = idVuelo.replace(":", "");
        int rows = jdbcTemplate.update("UPDATE vuelo SET capacidad_maxima = ? WHERE id_vuelo = ?", valor, idDb);
        if (rows > 0) {
            cargadorDatos.actualizarCapacidadVuelo(idDb, valor);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}
