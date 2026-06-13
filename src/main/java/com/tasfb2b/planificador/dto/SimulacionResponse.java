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
    private int k;           // factor de aceleración (K=1 día-a-día, K=14 sim-3días, K=144 colapso)
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
        /** Inicio del rango de datos consumidos, en hora LOCAL del eje de registro (= scStart).
         *  Ojo: mezcla husos (cada envío se registra en la hora local de su origen); no usar
         *  como reloj global. Para el eje UTC usar {@link #horaInicioUtc}. */
        private String horaInicio;
        /** Fin del rango de datos consumidos, en hora LOCAL del eje de registro (= scEnd). */
        private String horaFin;
        /** Rango UTC real de los registros contenidos en el bloque: el {@code registroUtc} más
         *  temprano entre sus asignaciones. Bien definido (a diferencia de convertir scStart, que
         *  mezcla husos). Útil para ubicar el bloque en el eje de tiempo global. Null si el bloque
         *  no tiene asignaciones con registro. */
        private String horaInicioUtc;
        /** Rango UTC real de los registros del bloque: el {@code registroUtc} más tardío. */
        private String horaFinUtc;
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
        /**
         * Nacimiento/registro del envío en hora local del aeropuerto de origen
         * (wall-clock, ISO sin offset). Es el instante desde el que las maletas
         * existen esperando en origen, antes de su primer vuelo.
         */
        private String registroLocal;
        /**
         * Mismo nacimiento expresado en UTC real (offset del origen aplicado).
         * Permite al front ubicar el envío en el eje de tiempo global desde que
         * nace, aunque su origen esté en otro huso.
         */
        private String registroUtc;
    }

    @Data
    public static class TramoRuta {
        private String vueloId;
        private String origen;
        private String destino;
        /** UTC real (offset del aeropuerto de origen aplicado). Despegue del tramo
         *  en el eje de tiempo global; usarlo para animar la posición del avión. */
        private String salidaUtc;
        /** UTC real (offset del aeropuerto de destino aplicado). Aterrizaje del tramo
         *  en el eje de tiempo global. */
        private String llegadaUtc;
        /** ISO datetime sin offset (hora de pared local del origen). Despegue del tramo. */
        private String salidaLocal;
        /** ISO datetime sin offset (hora de pared local del destino). Aterrizaje del tramo. */
        private String llegadaLocal;
        /**
         * Duración real del vuelo en minutos (UTC), ya con los husos aplicados.
         * Es {@code llegadaUtc − salidaUtc}. USAR ESTE valor para velocidad/animación;
         * NO restar los campos {@code *Local}, que están en husos distintos y dan
         * duraciones falsas (negativas o infladas).
         */
        private int duracionMin;
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

    /**
     * Serie temporal de ocupación de almacén por SLOT de 60 minutos — la granularidad nativa del
     * modelo interno — para que el front actualice EN VIVO las maletas de cada almacén mientras
     * su reloj de animación avanza dentro del bloque. {@code hora} es el INICIO del slot en eje
     * UTC; {@code ocupacion} es el ACUMULADO vigente del slot (estadías commiteadas de todos los
     * bloques hasta este, incluida la espera en origen de envíos sin ruta del backlog).
     * La expone {@code GET /jobs/{id}/almacenes/serie?desde=N}.
     */
    @Data
    public static class OcupacionAlmacenSlot {
        private String aeropuerto;
        /** Inicio del slot (ISO sin offset, eje UTC). El slot cubre [hora, hora+60min). */
        private String hora;
        private int capacidadMaxima;
        /** Maletas presentes a la vez en el almacén durante este slot (acumulado global). */
        private int ocupacion;
        private double porcentajeOcupacion;
        private String semaforo;
    }
}
