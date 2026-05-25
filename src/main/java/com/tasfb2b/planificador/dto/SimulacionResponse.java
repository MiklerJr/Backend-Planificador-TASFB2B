package com.tasfb2b.planificador.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SimulacionResponse {

    private Metricas metricas;
    private int totalBloques;
    private List<VueloBackend> vuelosPlaneados;
    private Map<String, AeropuertoDTO> aeropuertosInfo;

    // Parámetros de simulación para el frontend
    // Sc = k * saMinutos → cuántos minutos de datos consume el frontend por tick visual
    private int k;           // factor de aceleración (K=1 día-a-día, K=14 sim-3días, K=75 colapso)
    private int saMinutos;   // tamaño de ventana del planificador (Sa)

    @Data
    public static class Metricas {
        private int  procesadas;           // número de envíos (LuggageBatch)
        private int  enrutadas;            // envíos con ruta asignada
        private int  sinRuta;              // envíos sin ruta
        private int  cumpleSLA;            // envíos enrutados dentro del plazo
        private int  tardadas;             // envíos enrutados fuera del plazo
        private long maletasIndividuales;  // suma de cantidades físicas (bag count real)
        private int  vuelosCancelados;     // número de combinaciones vuelo-día canceladas
        private long tiempoEjecucionMs;
        private boolean collapsoDetectado; // escenario 3: true si se detectó colapso
        private int     bloqueColapso;     // escenario 3: índice del bloque donde ocurrió (-1 si no)

        // ── Métricas de calibración (modelo Ta/Sa) ─────────────────────────
        /** Ta mínimo observado en algún bloque (ms). */
        private long taMinMs;
        /** Ta máximo observado en algún bloque (ms). */
        private long taMaxMs;
        /** Ta promedio sobre todos los bloques procesados (ms). */
        private long taPromedioMs;
        /** Tiempo total dedicado al algoritmo (suma de Ta de todos los bloques, ms). */
        private long tiempoTotalAlgMs;
        /** Si Ta excedió 0.9 * Sa en algún bloque → la simulación necesita recalibrar K. */
        private boolean advertenciaCalibracion;

        // ── Métricas del backlog acumulativo ───────────────────────────────
        /** Tamaño del backlog al final de la simulación (sinRuta + replanificables). */
        private int backlogActual;
        /** Pico histórico del backlog durante la simulación. */
        private int backlogPico;
        /** Batches descartados definitivamente (SLA vencido o tope excedido). */
        private int sinRutaDefinitivo;
    }

    @Data
    public static class VueloBackend {
        private String id;
        private String origen;
        private String destino;
        private String fechaSalida;
        private String fechaLlegada;
        private int capacidadMaxima;
        private int cargaAsignada;
    }

    @Data
    public static class AeropuertoDTO {
        private String codigo;
        private double latitud;
        private double longitud;
        /** Capacidad real de almacen del aeropuerto, en maletas individuales. */
        private Integer capacidadAlmacen;
    }

    @Data
    public static class BloqueSimulacion {
        /** Inicio del rango de datos consumidos (eje de datos = scStart). */
        private String horaInicio;
        /** Fin del rango de datos consumidos (eje de datos = scEnd). */
        private String horaFin;
        /** Legacy: cantidad de envios/lotes procesados en este bloque (delta). */
        private int maletasProcesadas;
        /** Legacy: cantidad de envios/lotes enrutados en este bloque (delta). */
        private int maletasEnrutadas;
        /** Acumulado del job visible, en maletas individuales reales. */
        private long maletasProcesadasAcum;
        /** Acumulado enrutado del job visible, en maletas individuales reales. */
        private long maletasEnrutadasAcum;
        /** Acumulado entregado hasta horaFin, en maletas individuales reales. */
        private long maletasEntregadasAcum;
        private List<AsignacionMaleta> asignaciones;
        private List<CargaVuelo> cargasVuelos;
        private List<OcupacionAlmacen> ocupacionAlmacenes;

        // ── Modelo Ta/Sa: campos del eje real ──────────────────────────────
        /** Índice 0-based de este bloque en la simulación. */
        private int    bloqueIdx;
        /** {@code Ta} = duración real del procesamiento de este bloque, en ms. */
        private long   taMs;
        /** Cantidad de minutos de datos consumidos (Sc = K * Sa). */
        private int    scMinutos;

        private long tiempoProcesamientoMs;
    }

    @Data
    public static class AsignacionMaleta {
        private String batchId;
        private String origen;
        private String destino;
        /** Numero de maletas individuales dentro del envio/lote. */
        private int cantidad;
        private boolean enrutada;
        private boolean cumpleSLA;
        private List<String> rutaVuelos;
        /** Tramos con tiempos reales UTC; permite al frontend rastrear dónde está la maleta. */
        private List<TramoRuta> tramos;
    }

    @Data
    public static class TramoRuta {
        private String vueloId;
        private String origen;
        private String destino;
        /**
         * @deprecated El nombre miente — son {@code LocalDateTime} sin offset,
         * misma TZ del dataset (no UTC). Usar {@link #salidaLocal} en código
         * nuevo. Se mantiene en el JSON durante una versión por compatibilidad.
         */
        @Deprecated
        private String salidaUtc;
        /** @deprecated Idem {@link #salidaUtc}. Usar {@link #llegadaLocal}. */
        @Deprecated
        private String llegadaUtc;
        /** ISO datetime sin offset (hora local del dataset). Despegue del tramo. */
        private String salidaLocal;
        /** ISO datetime sin offset (hora local del dataset). Aterrizaje del tramo. */
        private String llegadaLocal;
    }

    @Data
    public static class CargaVuelo {
        private String vueloId;
        private String origen;
        private String destino;
        private String fechaSalida;
        private String fechaLlegada;
        private int capacidadMaxima;
        private int cargaAsignada;
        private double porcentajeCarga;
        private String semaforo;
    }

    @Data
    public static class OcupacionAlmacen {
        private String aeropuerto;
        private String fecha;
        private int capacidadMaxima;
        private int ocupacionAsignada;
        private double porcentajeOcupacion;
        private String semaforo;
    }
}
