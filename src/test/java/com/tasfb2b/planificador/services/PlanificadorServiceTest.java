package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.PlanificacionResultado;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanificadorServiceTest {

    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();
    private final EnvioLoader envioLoader = new EnvioLoader();
    private final PlanificadorService planificadorService =
            new PlanificadorService(aeropuertoLoader, graphBuilder, envioLoader);

    @Test
    void testProcesarPrimerosEnvios() {
        List<PlanificacionResultado> resultados = planificadorService.procesarConLimite("SKBO", 100);

        assertNotNull(resultados, "La lista de resultados no debe ser null");
        assertFalse(resultados.isEmpty(), "Debe haber resultados");

        int exitosos = 0;
        int fallidos = 0;

        for (PlanificacionResultado r : resultados) {
            if (r.exitoso) {
                exitosos++;
            } else {
                fallidos++;
            }
        }

        System.out.println("\n=== RESULTADOS DE PLANIFICACIÓN ===");
        System.out.println("Total envíos procesados: " + resultados.size());
        System.out.println("Exitosos: " + exitosos);
        System.out.println("Fallidos: " + fallidos);

        if (resultados.size() > 0) {
            System.out.println("\n=== PRIMEROS 5 RESULTADOS ===");
            for (int i = 0; i < Math.min(5, resultados.size()); i++) {
                PlanificacionResultado r = resultados.get(i);
                System.out.println("Envío: " + r.idEnvio +
                        " | Origen: " + r.origen +
                        " | Destino: " + r.destino +
                        " | Maletas: " + r.cantidadMaletas +
                        " | Éxito: " + r.exitoso +
                        (r.exitoso ? " | Ruta: " + String.join(" → ", r.ruta) +
                                " | Costo: " + r.costoTotal : " | Error: " + r.mensajeError));
            }
        }

        System.out.println("====================================\n");

        assertTrue(resultados.size() > 0, "Debe procesar al menos un envío");
    }

    @Test
    void testResumenEstadistico() {
        List<PlanificacionResultado> resultados = planificadorService.procesarTodosLosEnvios("SKBO");

        int countMaletas = 0;
        int countExitosos = 0;
        double costoTotalExitosos = 0.0;
        int rutasDirectas = 0;
        int rutasConEscala = 0;

        for (PlanificacionResultado r : resultados) {
            countMaletas += r.cantidadMaletas;
            if (r.exitoso) {
                countExitosos++;
                costoTotalExitosos += r.costoTotal;
                if (r.ruta.size() == 2) {
                    rutasDirectas++;
                } else if (r.ruta.size() > 2) {
                    rutasConEscala++;
                }
            }
        }

        System.out.println("\n=== ESTADÍSTICAS GENERALES ===");
        System.out.println("Total envíos: " + resultados.size());
        System.out.println("Total maletas: " + countMaletas);
        System.out.println("Envíos exitosos: " + countExitosos +
                " (" + String.format("%.2f", (countExitosos * 100.0 / resultados.size())) + "%)");
        System.out.println("Envíos fallidos: " + (resultados.size() - countExitosos));
        System.out.println("Costo promedio (exitosos): " +
                String.format("%.2f", countExitosos > 0 ? costoTotalExitosos / countExitosos : 0));
        System.out.println("Rutas directas: " + rutasDirectas);
        System.out.println("Rutas con escalas: " + rutasConEscala);
        System.out.println("================================\n");
    }
}