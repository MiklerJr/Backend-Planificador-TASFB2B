package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.dataset.IngestaEstado;
import com.tasfb2b.planificador.services.ingesta.IngestaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Ingesta de dataset por la página de carga del front. Reemplazo total y asíncrono.
 * Rutas bajo {@code /api/planificador}; CORS lo aporta el {@code CorsFilter} global; sin auth
 * (igual que el resto).
 */
@RestController
@RequestMapping("/api/planificador")
public class IngestaController {

    private final IngestaService ingesta;

    public IngestaController(IngestaService ingesta) {
        this.ingesta = ingesta;
    }

    /**
     * Sube un dataset completo y dispara el reemplazo total (async). Multipart con los campos
     * {@code aeropuertos} (1 archivo), {@code vuelos} (1 archivo) y {@code envios} (N archivos
     * {@code _envios_<ICAO>_.txt}).
     *
     * @return 202 + estado inicial si se aceptó; 409 si hay una simulación activa o ya hay una
     *         ingesta en curso; 400 si faltan archivos o un envío no tiene ICAO derivable del nombre.
     */
    @PostMapping(value = "/dataset/cargar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> cargar(
            @RequestParam("aeropuertos") MultipartFile aeropuertos,
            @RequestParam("vuelos")      MultipartFile vuelos,
            @RequestParam("envios")      MultipartFile[] envios) {
        try {
            IngestaEstado estado = ingesta.iniciar(aeropuertos, vuelos, envios);
            return ResponseEntity.accepted().body(estado);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** Estado de la ingesta para polling del front. 204 si nunca se ha iniciado una ingesta. */
    @GetMapping("/dataset/cargar/estado")
    public ResponseEntity<IngestaEstado> estado() {
        IngestaEstado estado = ingesta.getEstado();
        if (estado == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(estado);
    }
}
