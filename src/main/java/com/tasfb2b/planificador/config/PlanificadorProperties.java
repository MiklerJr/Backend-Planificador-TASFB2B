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

    private Scenario     scenario     = new Scenario();
    private Alns         alns         = new Alns();
    private Objetivo     objetivo     = new Objetivo();
    private Backlog      backlog      = new Backlog();
    private StorageAware storageAware = new StorageAware();
    private AlertaColapso alertaColapso = new AlertaColapso();

    /** Parámetros de planificación programada fija (Sa, Ta, K, umbrales globales). */
    @Data
    public static class Scenario {
        /** Salto del algoritmo (Sa) en minutos. Eje de datos: cada bloque consume K*Sa min. */
        private int saMinutos = 10;

        /**
         * Tiempo del algoritmo (Ta) en segundos — presupuesto FIJO de cómputo por bloque.
         *
         * <p>Es una variable de configuración del modelo, NO una medición. Cada bloque corre
         * el motor durante exactamente {@code Ta} segundos (cota dura) y luego duerme
         * {@code Sa - Ta} para que el wall-clock por bloque sea siempre Sa.
         *
         * <p>Restricción del modelo: {@code Ta < Sa}. Si {@code Ta = 0} se usa el legacy
         * {@code 0.7·Sa} como presupuesto.
         */
        private int taSegundos = 0;
        /** K por defecto para escenario día a día (tiempo real). */
        private int kDefault1 = 1;
        /** K por defecto para escenario de período (3/5/7 días). */
        private int kDefault2 = 14;
        /** K por defecto para escenario hasta colapso. */
        private int kDefault3 = 144;
        /** Tasa de sinRuta por bloque que dispara el flag de colapso (escenario 3). */
        private double umbralColapso = 0.20;
        /** Tope absoluto de backlog que también dispara colapso por saturación. */
        private int umbralColapsoBacklog = 8000;
        /** Si true, escenario 1 duerme (Sa - Ta) entre bloques. Por diseño (día a día). */
        private boolean simularTiempoReal1 = true;
        /** Si true, escenario 2 duerme (Sa - Ta) entre bloques para simular tiempo real. */
        private boolean simularTiempoReal2 = true;
        /** Si true, escenario 3 también duerme entre bloques (no recomendado). */
        private boolean simularTiempoReal3 = false;

        /**
         * Fase T (N3) — si true, antes del bucle de bloques de E2 se pre-calienta la caché de
         * esqueletos con la demanda de la ventana (Dijkstra fuera del presupuesto Ta). Sube el
         * throughput en arranque limpio (caché fría). Ta-safe; no cambia rutas. {@code false} = off.
         */
        private boolean prewarmSkeletons = true;

        /**
         * Límite máximo de ventanas a procesar (para acotar archivos enormes).
         * Ej: 720 ventanas de 10 min = 5 días.
         * Valor por defecto 0 significa que lee todo el archivo.
         */
        private int maxVentanas = 0;

        /**
         * Anti-OOM (Fase 1): nº de bloques RECIENTES cuyas {@code asignaciones} y series por slot se
         * retienen en RAM por job; los más viejos se purgan ({@code JobState.publicarBloque}). El front
         * consume {@code /bloques} y {@code /asignaciones} incrementalmente, así que solo necesita la
         * ventana reciente. Bajarlo reduce el pico de RAM; subirlo da más holgura si el front se atrasa.
         */
        private int maxBloquesBuffer = 60;

        /**
         * Anti-OOM (Fase 2): nº de jobs TERMINADOS más recientes que conservan sus estructuras pesadas
         * en RAM (para que el front siga animando el último que terminó). De los más antiguos se liberan
         * bloques/acumuladores (sus rutas viven en BD y el ZIP de auditoría en disco). Los jobs activos
         * nunca se tocan. Evita que correr varias simulaciones acumule RAM hasta reiniciar.
         */
        private int maxJobsEnMemoria = 3;

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
        /**
         * Fase M (anti-thrash): máximo de envíos del backlog a reprocesar por bloque,
         * tomando los de deadline más cercano. El resto se difiere al siguiente bloque
         * (sin perderse). 0 = sin tope (comportamiento original). Acota que un backlog
         * grande le robe Ta a la demanda nueva y dispare violaciones de Ta.
         */
        private int maxReprocesoPorBloque = 0;
    }

    /**
     * Umbrales de la alerta de colapso logístico INMINENTE (pre-colapso). Solo informa
     * (consola + endpoint), no detiene. Override con {@code planificador.alerta-colapso.*}.
     */
    @Data
    public static class AlertaColapso {
        /** Utilización de almacén (0..1) a partir de la cual la alerta es ÁMBAR. */
        private double almacenAmbar = 0.85;
        /** Utilización de almacén (0..1) a partir de la cual la alerta es ROJO. */
        private double almacenRojo = 0.95;
        /** Holgura SLA restante (fracción 0..1) por debajo de la cual la alerta es ÁMBAR. */
        private double slaRestanteAmbar = 0.25;
        /** Holgura SLA restante (fracción 0..1) por debajo de la cual la alerta es ROJO. */
        private double slaRestanteRojo = 0.10;
    }

    /**
     * Fase L/O/P — perillas del enrutado storage-aware (libera almacén-día de hub para los SLA
     * cortos). Expuestas para barrer valores SIN recompilar (la dirección está probada: cada
     * subida de reserva / bajada de umbral / curva más agresiva ha movido el primer fallo).
     */
    @Data
    public static class StorageAware {
        /**
         * L2 — colchón de reserva en almacén-día de hub para escalas overnight de envíos flexibles
         * (escalado por holgura). 0 = sin reserva de almacén. La 2ª pasada con 0 siempre existe en
         * el motor, así que nunca causa un sinRuta evitable (invariante anti-J3).
         */
        private double reservaAlmacenBase = 0.15;
        /**
         * O — fracción de capacidad de almacén a partir de la cual un aeropuerto se marca hub
         * (utilización-pico). Más bajo = protege antes / a más aeropuertos.
         */
        private double umbralHubPico = 0.55;
        /**
         * L1/P — exponente p de la curva de precio de almacén-hub {@code u^p/(1−u)}. p<2 muerde
         * antes (p=1.7 ⇒ desde ~0.35; p=2 ⇒ desde ~0.45). Menor p = la selección evita hubs con
         * más anticipación.
         */
        private double precioHubExponente = 1.7;
        /**
         * Fase Q — máximo de claves de esqueleto a re-sembrar con rutas hub-avoiding por bloque
         * (amortizado, usando el tiempo ocioso del bloque acotado por el deadline de Ta). 0 =
         * desactivar el re-seed. Solo agrega opciones a la caché (nunca quita la ruta rápida).
         */
        private int reSeedSlice = 256;
    }

}
