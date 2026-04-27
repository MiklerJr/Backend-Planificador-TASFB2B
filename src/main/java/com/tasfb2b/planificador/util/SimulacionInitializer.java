package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.dto.SimulacionResponse;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Scanner;

@Component
public class SimulacionInitializer implements CommandLineRunner {

    private final PlanificadorService planificadorService;

    public SimulacionInitializer(PlanificadorService planificadorService) {
        this.planificadorService = planificadorService;
    }

    @Override
    public void run(String... args) {
        imprimirBanner();

        // Modo "rest-only": salta el menú y deja solo el servidor REST activo.
        // Útil para validación con curl o para evitar bloqueo en stdin.
        for (String a : args) {
            if ("--rest-only".equalsIgnoreCase(a) || "rest-only".equalsIgnoreCase(a)) {
                System.out.println("  Modo REST-only: el menú interactivo está deshabilitado.");
                System.out.println("  Usa los endpoints /api/planificador/... para lanzar simulaciones.");
                System.out.println();
                return;
            }
        }

        Scanner sc = new Scanner(System.in);
        int    escenario  = leerEscenario(sc);
        double cancelProb = leerCancelProb(sc);

        System.out.println();
        switch (escenario) {
            case 1 -> ejecutarEscenario1(cancelProb);
            case 2 -> ejecutarEscenario2(cancelProb);
            case 3 -> ejecutarEscenario3(cancelProb);
        }

        System.out.println("  El servidor REST sigue activo. Usa los endpoints /api/planificador/... para consultas.");
    }

    // ── Menú inicial ─────────────────────────────────────────────────────────

    private static void imprimirBanner() {
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════════════════╗");
        System.out.println("  ║          PLANIFICADOR DE MALETAS — TASFB2B               ║");
        System.out.println("  ╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static int leerEscenario(Scanner sc) {
        System.out.println("  Selecciona el escenario de simulación:");
        System.out.println("    [1] Día a día         — K=1,  ventana por ventana (interactivo)");
        System.out.println("    [2] Período completo  — K=14, procesa todas las ventanas de una vez");
        System.out.println("    [3] Hasta el colapso  — K=75, se detiene al superar el umbral de fallo");
        System.out.print("\n  Escenario [1/2/3]: ");

        while (sc.hasNextLine()) {
            String l = sc.nextLine().trim();
            if (l.equals("1") || l.equals("2") || l.equals("3")) return Integer.parseInt(l);
            System.out.print("  Opción no válida. Ingresa 1, 2 o 3: ");
        }
        return 2;
    }

    private static double leerCancelProb(Scanner sc) {
        System.out.print("  Probabilidad de cancelación de vuelos por día [0-100]%: ");
        while (sc.hasNextLine()) {
            String l = sc.nextLine().trim().replace(",", ".");
            try {
                double v = Double.parseDouble(l);
                if (v >= 0 && v <= 100) return v / 100.0;
            } catch (NumberFormatException ignored) {}
            System.out.print("  Valor no válido (escribe un número entre 0 y 100): ");
        }
        return 0.0;
    }

    // ── Escenario 1: día a día ────────────────────────────────────────────────

    private void ejecutarEscenario1(double cancelProb) {
        System.out.println("  ▶ Inicializando escenario 1...");
        Map<String, Object> estado = planificadorService.inicializarEscenario1(cancelProb);
        int total = (int) estado.get("totalVentanas");
        System.out.printf("    %d ventanas listas.%n%n", total);

        int procesadas = 0;
        while (true) {
            SimulacionResponse.BloqueSimulacion bloque;
            try {
                bloque = planificadorService.procesarSiguienteVentana();
            } catch (IllegalStateException e) {
                System.out.println("  Error: " + e.getMessage());
                break;
            }

            if (bloque == null) {
                System.out.println("  ✓ Todas las ventanas procesadas.");
                System.out.println();
                imprimirEstadoAcumulado(planificadorService.getEstadoEscenario1());
                break;
            }

            procesadas++;
            imprimirBloque(bloque, procesadas, total);
        }
    }

    private static void imprimirBloque(SimulacionResponse.BloqueSimulacion b, int idx, int total) {
        var asigs     = b.getAsignaciones();
        int enrutados = b.getMaletasEnrutadas();
        int sinRuta   = b.getMaletasProcesadas() - enrutados;
        int cumpleSLA = (int) asigs.stream().filter(a -> a.isEnrutada() &&  a.isCumpleSLA()).count();
        int tardados  = (int) asigs.stream().filter(a -> a.isEnrutada() && !a.isCumpleSLA()).count();

        System.out.printf("  ┌─ Ventana %d / %d ─────────────────────────────────┐%n", idx, total);
        System.out.printf("  │ %s  →  %s%n", b.getHoraInicio(), b.getHoraFin());
        System.out.printf("  │ Envíos : %-5d  Enrutados: %-5d  Sin ruta: %-5d%n",
                b.getMaletasProcesadas(), enrutados, sinRuta);
        System.out.printf("  │ SLA OK : %-5d  Tardados : %-5d%n", cumpleSLA, tardados);
        System.out.println("  └──────────────────────────────────────────────────┘");
        System.out.println();
    }

    private static void imprimirEstadoAcumulado(Map<String, Object> e) {
        System.out.println("  ── Estado acumulado ──────────────────────────────────────");
        System.out.printf("  Ventana actual    : %s / %s%n", e.get("ventanaActual"), e.get("totalVentanas"));
        System.out.printf("  Total envíos      : %s%n", e.get("totalEnvios"));
        System.out.printf("  Enrutados         : %s%n", e.get("totalEnrutadas"));
        System.out.printf("  Sin ruta          : %s%n", e.get("totalSinRuta"));
        System.out.printf("  Cumplen SLA       : %s%n", e.get("totalCumpleSLA"));
        System.out.printf("  Tardados          : %s%n", e.get("totalTardadas"));
        System.out.printf("  Maletas (unidades): %s%n", e.get("totalMaletas"));
        System.out.println("  ─────────────────────────────────────────────────────────");
        System.out.println();
    }

    // ── Escenario 2: período completo ─────────────────────────────────────────

    private void ejecutarEscenario2(double cancelProb) {
        System.out.println("  ▶ Ejecutando escenario 2 (período completo, K=14)...");
        long inicio = System.currentTimeMillis();
        SimulacionResponse res = planificadorService.ejecutarALNS(14, cancelProb);
        long ms = System.currentTimeMillis() - inicio;
        imprimirResumen(res, ms);
    }

    // ── Escenario 3: hasta el colapso ─────────────────────────────────────────

    private void ejecutarEscenario3(double cancelProb) {
        System.out.println("  ▶ Ejecutando escenario 3 (hasta el colapso, K=75)...");
        long inicio = System.currentTimeMillis();
        SimulacionResponse res = planificadorService.ejecutarHastaColapso(75, cancelProb, 0.20);
        long ms = System.currentTimeMillis() - inicio;
        imprimirResumen(res, ms);

        SimulacionResponse.Metricas m = res.getMetricas();
        if (m.isCollapsoDetectado()) {
            System.out.printf("  ⚠  COLAPSO detectado en el bloque %d%n%n", m.getBloqueColapso());
        } else {
            System.out.println("  ✓  Simulación completada sin colapso logístico.");
            System.out.println();
        }
    }

    // ── Helpers de presentación ───────────────────────────────────────────────

    private static void imprimirResumen(SimulacionResponse res, long ms) {
        SimulacionResponse.Metricas m = res.getMetricas();
        System.out.println();
        System.out.println("  ═══════════════════════════════════════════════════════════");
        System.out.println("  RESULTADOS");
        System.out.println("  ═══════════════════════════════════════════════════════════");
        System.out.printf("  Bloques procesados  : %d%n",  res.getTotalBloques());
        System.out.printf("  Total envíos        : %d%n",  m.getProcesadas());
        System.out.printf("  Enrutados           : %d%n",  m.getEnrutadas());
        System.out.printf("  Sin ruta            : %d%n",  m.getSinRuta());
        System.out.printf("  Cumplen SLA         : %d%n",  m.getCumpleSLA());
        System.out.printf("  Tardados            : %d%n",  m.getTardadas());
        System.out.printf("  Maletas (unidades)  : %d%n",  m.getMaletasIndividuales());
        if (m.getVuelosCancelados() > 0)
            System.out.printf("  Vuelos cancelados   : %d%n", m.getVuelosCancelados());
        System.out.printf("  Tiempo de ejecución : %d ms%n", ms);
        System.out.println("  ═══════════════════════════════════════════════════════════");
        System.out.println();
    }
}
