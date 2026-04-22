package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
@RestController
@RequestMapping("/api/planificador")
public class PlanificadorController {

    @Autowired
    private PlanificadorService service;

    @GetMapping("/ejecutar")
    public ResponseEntity<SimulacionResponse> obtenerPlanActual(
            @RequestParam(defaultValue = "alns") String algoritmo) {

        SimulacionResponse respuesta;

        // Aquí evaluamos qué "zapato" usar según lo que pida el frontend
        if ("aco".equalsIgnoreCase(algoritmo)) {
            respuesta = service.ejecutarACO();
        } else {
            // Por defecto (Zapato Rojo - ALNS)
            respuesta = service.getUltimaSimulacion();
            if (respuesta == null) {
                respuesta = service.ejecutarALNS();
            }
        }

        return ResponseEntity.ok(respuesta);
    }
}