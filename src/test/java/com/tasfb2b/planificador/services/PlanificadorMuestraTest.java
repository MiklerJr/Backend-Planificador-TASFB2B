package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.ResumenPlanificacionGlobal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanificadorMuestraTest {

    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();
    private final EnvioLoader envioLoader = new EnvioLoader();
    private final PlanificadorService planificadorService =
            new PlanificadorService(aeropuertoLoader, graphBuilder, envioLoader);

    @Test
    void testProcesarMuestra() {
        System.out.println("Iniciando procesamiento de muestra representativa...\n");

        ResumenPlanificacionGlobal resumen = planificadorService.procesarTodosLosOrigenesConLimite(100);

        System.out.println("\n=== RESUMEN GLOBAL ===");
        System.out.println("Total envíos procesados: " + resumen.totalEnviosProcesados);
        System.out.println("Envíos exitosos: " + resumen.totalEnviosExitosos +
                " (" + String.format("%.2f", (resumen.totalEnviosExitosos * 100.0 / resumen.totalEnviosProcesados)) + "%)");
        System.out.println("Envíos fallidos: " + resumen.totalEnviosFallidos);
        System.out.println("Total maletas: " + resumen.totalMaletas);
        System.out.println("Tiempo de ejecución: " + resumen.tiempoEjecucionMs + " ms");

        System.out.println("\n=== ESTADÍSTICAS POR ORIGEN ===");
        for (Map.Entry<String, ResumenPlanificacionGlobal.EstadisticaOrigen> entry :
                resumen.estadisticasPorOrigen.entrySet()) {
            ResumenPlanificacionGlobal.EstadisticaOrigen stat = entry.getValue();
            double porcentajeExito = stat.totalEnvios > 0
                    ? (stat.exitosos * 100.0 / stat.totalEnvios) : 0;

            System.out.println(stat.origen + ": " +
                    stat.totalEnvios + " envíos, " +
                    stat.exitosos + " exitosos (" + String.format("%.1f", porcentajeExito) + "%), " +
                    stat.fallidos + " fallidos, " +
                    stat.rutasConEscala + " con escalas");
        }

        System.out.println("\n====================================\n");

        assertNotNull(resumen);
        assertTrue(resumen.totalEnviosProcesados > 0);
    }
}