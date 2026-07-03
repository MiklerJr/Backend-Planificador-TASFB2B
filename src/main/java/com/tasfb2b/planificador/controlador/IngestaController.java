package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.dto.datos.IngestaEstado;
import com.tasfb2b.planificador.servicios.ingesta.IngestaService;
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

@RestController
@RequestMapping("/api/planificador")
public class IngestaController {

    private final IngestaService ingesta;

    public IngestaController(IngestaService ingesta) {
        this.ingesta = ingesta;
    }

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

    @GetMapping("/dataset/cargar/estado")
    public ResponseEntity<IngestaEstado> estado() {
        IngestaEstado estado = ingesta.getEstado();
        if (estado == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(estado);
    }
}
