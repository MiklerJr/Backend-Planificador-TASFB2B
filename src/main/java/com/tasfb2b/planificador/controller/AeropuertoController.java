package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.AeropuertoRequest;
import com.tasfb2b.planificador.dto.AeropuertoResponse;
import com.tasfb2b.planificador.services.AeropuertoService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/aeropuertos")
public class AeropuertoController {

    private final AeropuertoService service;

    public AeropuertoController(AeropuertoService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<AeropuertoResponse>> listar() {
        return ResponseEntity.ok(service.listar());
    }

    @GetMapping("/{codigo}")
    public ResponseEntity<AeropuertoResponse> obtener(@PathVariable String codigo) {
        AeropuertoResponse res = service.obtener(codigo);
        return res == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(res);
    }

    @PostMapping
    public ResponseEntity<AeropuertoResponse> crear(@Valid @RequestBody AeropuertoRequest request) {
        return ResponseEntity.ok(service.crear(request));
    }

    @PutMapping("/{codigo}")
    public ResponseEntity<AeropuertoResponse> actualizar(@PathVariable String codigo,
                                                        @Valid @RequestBody AeropuertoRequest request) {
        AeropuertoResponse res = service.actualizar(codigo, request);
        return res == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(res);
    }

    @DeleteMapping("/{codigo}")
    public ResponseEntity<Void> eliminar(@PathVariable String codigo) {
        return service.eliminar(codigo) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
