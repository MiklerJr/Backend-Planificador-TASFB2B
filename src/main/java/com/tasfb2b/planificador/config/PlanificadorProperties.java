package com.tasfb2b.planificador.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Hiperparámetros externalizados del planificador. Cargados desde application.yaml
 * bajo el prefijo "planificador". Los valores hardcoded heredados quedan como
 * defaults aquí para mantener equivalencia con el comportamiento previo.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "planificador")
public class PlanificadorProperties {

    private Scenario  scenario  = new Scenario();
    private Alns      alns      = new Alns();
    private Objetivo  objetivo  = new Objetivo();
    private Backlog   backlog   = new Backlog();
    private Benchmark benchmark = new Benchmark();

    /** Parámetros de planificación programada fija (Sa, K, umbrales globales). */
    @Data
    public static class Scenario {
        /** Salto del algoritmo (Sa) en minutos. Eje de datos: cada bloque consume K*Sa min. */
        private int saMinutos = 10;
        /** K por defecto para escenario día a día (tiempo real). */
        private int kDefault1 = 1;
        /** K por defecto para escenario de período (3/5/7 días). */
        private int kDefault2 = 14;
        /** K por defecto para escenario hasta colapso. */
        private int kDefault3 = 75;
        /** Tasa de sinRuta por bloque que dispara el flag de colapso (escenario 3). */
        private double umbralColapso = 0.20;
        /** Tope absoluto de backlog que también dispara colapso por saturación. */
        private int umbralColapsoBacklog = 8000;
        /** Si true, escenario 2 duerme (Sa - Ta) entre bloques para simular tiempo real. */
        private boolean simularTiempoReal2 = true;
        /** Si true, escenario 3 también duerme entre bloques (no recomendado). */
        private boolean simularTiempoReal3 = false;

        /**
         * Límite máximo de ventanas a procesar (para acotar archivos enormes).
         * Ej: 720 ventanas de 10 min = 5 días.
         * Valor por defecto 0 significa que lee todo el archivo.
         */
        private int maxVentanas = 0;

        public int getMaxVentanas() {
            return maxVentanas;
        }

        public void setMaxVentanas(int maxVentanas) {
            this.maxVentanas = maxVentanas;
        }
    }

    /** Hiperparámetros internos del ALNS y su Simulated Annealing. */
    @Data
    public static class Alns {
        /** Iteraciones ALNS por bloque en operación normal. */
        private int iteracionesBase = 9;
        /** Iteraciones ALNS por bloque cuando la tasa de sinRuta sube (cerca del colapso). */
        private int iteracionesCercaColapso = 25;
        /** Fracción de lotes destruidos por iteración. */
        private double destroyFactor = 0.20;
        /** Temperatura SA inicial. */
        private double initialTemp = 500.0;
        /** Factor de enfriamiento por iteración. */
        private double coolingRate = 0.85;
        /** Temperatura mínima (criterio de parada anticipada). */
        private double minTemp = 0.1;
        /** Bloques con menos batches que esto no ejecutan ALNS (solo greedy). */
        private int minBlockSize = 3;
        /** Iteraciones entre actualizaciones de pesos de operadores destroy. */
        private int segmentLength = 3;
        /** Factor de reacción r ∈ (0,1] de Ropke & Pisinger 2006. */
        private double reactionFactor = 0.15;
        /** Lista de operadores destroy a usar. Por ahora solo capacity y worst-route están implementados. */
        private List<String> operadoresDestroy = List.of("capacity", "worst-route");
    }

    /** Pesos de la función objetivo del ALNS. */
    @Data
    public static class Objetivo {
        /** Peso multiplicador del tiempo de tránsito acumulado (suma de minutos). */
        private double pesoTransit = 1.0;
        /** Penalización fija por cada batch tardado (incumplimiento de SLA). */
        private double pesoTarde = 5000.0;
        /** Peso del término de uso de almacén (premia distribución, fase 5). */
        private double pesoUsoAlmacen = 50.0;
        /** Peso del término de uso de vuelo (premia distribución, fase 5). */
        private double pesoUsoVuelo = 20.0;
    }

    /** Backlog acumulativo de pedidos pendientes/replanificables (fase 4). */
    @Data
    public static class Backlog {
        /** Tope absoluto de batches en backlog. Excedente se mueve a sinRutaDefinitivo. */
        private int maxSize = 10000;
        /** Si true, se descartan batches cuyo SLA ya venció antes de reintentar. */
        private boolean purgarVencidas = true;
        /** Slack relativo (margen al SLA) bajo el cual una ruta se considera "próxima a tardar". */
        private double umbralReplanificacionSlack = 0.10;
        /** Máximo de replanificaciones preventivas por bloque (cota de cómputo). */
        private int maxReplanificacionesPorBloque = 20;
    }

    /** Configuración del endpoint de calibración (fase 6). */
    @Data
    public static class Benchmark {
        /** Valores de K a probar en el grid. */
        private List<Integer> kGrid = List.of(1, 7, 14, 28, 50, 75, 100);
        /** Probabilidades de cancelación a probar en el grid. */
        private List<Double> cancelProbGrid = List.of(0.0, 0.05, 0.10);
        /** Repeticiones por combinación (para promediar Ta). */
        private int repeticiones = 3;
        /** Timeout máximo del benchmark completo en minutos. */
        private int timeoutMinutos = 90;
    }
}
