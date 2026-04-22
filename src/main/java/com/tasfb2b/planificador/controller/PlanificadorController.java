package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
@RestController
@RequestMapping("/api/planificador")
public class PlanificadorController {

    @Autowired
    private PlanificadorService service;

    @GetMapping("/ejecutar")
    public ResponseEntity<?> obtenerPlanActual() {
        // En lugar de calcularlo CADA VEZ, devolvemos lo que ya está en memoria
        // o disparamos un cálculo rápido si es necesario.
        Map<String, Object> respuesta = new HashMap<>();

        respuesta.put("vuelosPlaneados", service.getVuelosCalculados());
        respuesta.put("aeropuertosInfo", service.getMapaAeropuertos()); // ¡Esto es vital para el mapa!

        Map<String, Object> metricas = new HashMap<>();
        metricas.put("procesadas", service.getTotalPedidos());
        metricas.put("enrutadas", service.getVuelosCalculados().size());
        respuesta.put("metricas", metricas);

        return ResponseEntity.ok(respuesta);
    }
}