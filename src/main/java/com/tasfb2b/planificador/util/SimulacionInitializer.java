package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SimulacionInitializer implements CommandLineRunner {

    private final PlanificadorService planificadorService;

    public SimulacionInitializer(PlanificadorService planificadorService) {
        this.planificadorService = planificadorService;
    }

    @Override
    public void run(String... args) {
        System.out.println("🚀 [SISTEMA] Arrancando motor ALNS de forma autónoma...");
        try {
            // Aquí llamas a tu lógica de inicialización y cálculo
            planificadorService.ejecutarALNS(14); // K=14: simulación de 3 días por defecto
            System.out.println("✅ [SISTEMA] Planificación inicial completada y lista en memoria.");
        } catch (Exception e) {
            System.err.println("❌ [ERROR] Falló la ejecución inicial: " + e.getMessage());
        }
    }
}
