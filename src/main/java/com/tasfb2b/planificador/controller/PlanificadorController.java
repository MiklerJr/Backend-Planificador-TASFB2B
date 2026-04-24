package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
@RestController
@RequestMapping("/api/planificador")
public class PlanificadorController {

    private final PlanificadorService service;

    public PlanificadorController(PlanificadorService service) {
        this.service = service;
    }

    /**
     * Ejecuta la planificación de pedidos-rutas.
     *
     * @param algoritmo  "alns" (default) o "aco"
     * @param k          Factor de aceleración de la simulación:
     *                   K=1  → operaciones día a día (tiempo real)
     *                   K=14 → simulación de 3 días (default)
     *                   K=75 → simulación hasta el colapso logístico
     */
    /**
     * Ejecuta la planificación de pedidos-rutas.
     *
     * @param algoritmo   "alns" (default) o "aco"
     * @param k           Factor de aceleración: K=1 día a día, K=14 sim-3días (default), K=75 colapso
     * @param cancelProb  Probabilidad de cancelación de vuelo-día [0.0–1.0], default 0
     */
    @GetMapping("/ejecutar")
    public ResponseEntity<SimulacionResponse> ejecutar(
            @RequestParam(defaultValue = "alns") String algoritmo,
            @RequestParam(defaultValue = "14")   int    k,
            @RequestParam(defaultValue = "0.0")  double cancelProb) {

        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb)); // clamp al rango válido
        return switch (algoritmo.toLowerCase()) {
            case "alns" -> ResponseEntity.ok(service.ejecutarALNS(k, cancelProb));
            case "aco"  -> ResponseEntity.ok(service.ejecutarACO());
            default     -> ResponseEntity.badRequest().build();
        };
    }

    @GetMapping("/bloque/{index}")
    public ResponseEntity<SimulacionResponse.BloqueSimulacion> getBloque(@PathVariable int index) {
        SimulacionResponse.BloqueSimulacion bloque = service.getBloque(index);
        if (bloque == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(bloque);
    }

    // ── Escenario 3: hasta el colapso ────────────────────────────────────────

    @GetMapping("/ejecutar-colapso")
    public ResponseEntity<SimulacionResponse> ejecutarColapso(
            @RequestParam(defaultValue = "75")   int    k,
            @RequestParam(defaultValue = "0.1")  double cancelProb,
            @RequestParam(defaultValue = "0.20") double umbralColapso) {

        cancelProb    = Math.max(0.0, Math.min(1.0, cancelProb));
        umbralColapso = Math.max(0.0, Math.min(1.0, umbralColapso));
        return ResponseEntity.ok(service.ejecutarHastaColapso(k, cancelProb, umbralColapso));
    }

    // ── Escenario 1: día a día ────────────────────────────────────────────────

    @PostMapping("/escenario1/inicializar")
    public ResponseEntity<Map<String, Object>> inicializarEsc1(
            @RequestParam(defaultValue = "0.0") double cancelProb) {

        cancelProb = Math.max(0.0, Math.min(1.0, cancelProb));
        return ResponseEntity.ok(service.inicializarEscenario1(cancelProb));
    }

    @GetMapping("/escenario1/ventana")
    public ResponseEntity<SimulacionResponse.BloqueSimulacion> siguienteVentana() {
        try {
            SimulacionResponse.BloqueSimulacion bloque = service.procesarSiguienteVentana();
            if (bloque == null) return ResponseEntity.noContent().build(); // 204 = fin
            return ResponseEntity.ok(bloque);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/escenario1/estado")
    public ResponseEntity<Map<String, Object>> estadoEsc1() {
        return ResponseEntity.ok(service.getEstadoEscenario1());
    }

    @GetMapping("/escenario1/bloque/{index}")
    public ResponseEntity<SimulacionResponse.BloqueSimulacion> getBloqueEsc1(@PathVariable int index) {
        SimulacionResponse.BloqueSimulacion bloque = service.getBloqueEsc1(index);
        if (bloque == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(bloque);
    }
}