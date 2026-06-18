package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.VueloRequest;
import com.tasfb2b.planificador.dto.VueloResponse;
import com.tasfb2b.planificador.services.VueloService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vuelos")
public class VueloController {

    private final VueloService service;

    public VueloController(VueloService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<VueloResponse>> listar() {
        return ResponseEntity.ok(service.listar());
    }

    @GetMapping("/{id}")
    public ResponseEntity<VueloResponse> obtener(@PathVariable String id) {
        VueloResponse res = service.obtener(id);
        return res == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(res);
    }

    @PostMapping
    public ResponseEntity<VueloResponse> crear(@Valid @RequestBody VueloRequest request) {
        return ResponseEntity.ok(service.crear(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VueloResponse> actualizar(@PathVariable String id,
                                                    @Valid @RequestBody VueloRequest request) {
        VueloResponse res = service.actualizar(id, request);
        return res == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(res);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable String id) {
        return service.eliminar(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
