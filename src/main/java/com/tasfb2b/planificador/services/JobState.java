package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.AlertaColapso;
import com.tasfb2b.planificador.dto.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.*;
import com.tasfb2b.planificador.dto.VueloCancelado;
import lombok.Data;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Estado de una ejecución asíncrona del planificador.
 * Permite consultar progreso desde endpoints REST mientras la simulación corre
 * en un hilo dedicado (escenarios 2 y 3 pueden tomar 30-90 min con sleep activo).
 */
@Data
public class JobState {
    /** Identificador único del job (UUID). */
    private final String jobId;
    /** "2" para período, "3" para colapso. */
    private final String escenario;
    /** Factor K de aceleración. */
    private final int k;

    /**
     * Estado del job. Transiciones válidas:
     * <pre>
     *   encolado ──► ejecutando ──► completado
     *           │              │
     *           │              ├──► calentando ──► ejecutando ──► completado
     *           │              │
     *           ▼              ▼
     *        cancelado      cancelado | error
     * </pre>
     *
     * <p>Valores: {@code "encolado"} (esperando turno en el executor),
     * {@code "calentando"} (simulando hasta fechaInicio), {@code "ejecutando"}
     * (procesando bloques visibles), {@code "completado"}, {@code "cancelado"},
     * {@code "error"}.
     */
    public volatile String estado = "encolado";

    /**
     * True si el job terminó porque el usuario llamó a {@code /cancelar}.
     * Permite distinguir cancelación voluntaria de fallo real. Cuando es
     * {@code true}, {@code estado="cancelado"} y {@code error} es null.
     */
    public volatile boolean canceladoPorUsuario = false;

    /**
     * Alerta de colapso logístico INMINENTE del último bloque procesado (pre-colapso). VERDE/AMBAR/
     * ROJO. Solo informa; el colapso real se refleja en estado="completado" + métricas. La expone
     * {@code GET /jobs/{id}/alerta-colapso} y {@code /jobs/{id}/estado}.
     */
    public volatile AlertaColapso alertaColapso;

    /** Bloque actualmente procesado (1-based, 0 antes de iniciar). */
    public volatile int bloqueActual = 0;
    /** Total de bloques previstos (se conoce tras construir el plan). */
    public volatile int totalBloques = 0;

    /**
     * Progreso del warm-up cuando {@code fechaInicio} obliga a simular el
     * período [primera-ventana-del-dataset, fechaInicio) antes de empezar a
     * publicar bloques al front. Mientras dura, {@link #estado} = "calentando"
     * y el front muestra un indicador de "esperando a alcanzar la fecha inicial".
     * Si no hay warm-up (sin {@code fechaInicio} o fechaInicio ≤ primera ventana),
     * ambos quedan en 0.
     */
    public volatile int bloqueWarmup = 0;
    public volatile int totalBloquesWarmup = 0;
    /** Promedio de Ta hasta el bloque actual (ms). */
    public volatile long taPromedioMs = 0L;

    /** Resultado completo cuando estado = "completado". Null mientras ejecuta. */
    public volatile SimulacionResponse resultado;

    /**
     * Snapshot del ESTADO INICIAL para el front cuando el job arrancó con {@code fechaInicio} +
     * warm-up: las asignaciones pre-calculadas cuyos envíos siguen ACTIVOS al terminar el warm-up
     * (en vuelo, en escala o con tramos futuros), con sus tramos UTC completos — lo que el mapa
     * necesita para pintar los aviones que ya están en el aire en fechaInicio. Null mientras se
     * calcula (estado "encolado"/"calentando"); lista vacía si no hubo warm-up. La expone
     * {@code GET /jobs/{id}/estado-inicial}.
     */
    public volatile List<AsignacionMaleta> estadoInicial;
    /** Mensaje de error cuando estado = "error". */
    public volatile String error;
    /**
     * CSV de auditoría por envío (23 columnas). Generado al terminar el job y
     * descargable vía {@code GET /api/planificador/jobs/{jobId}/auditoria.csv}.
     */
    public volatile String auditoriaCsv;
    /** Ruta temporal del CSV de auditoria cuando es demasiado grande para mantenerlo en heap. */
    public volatile Path auditoriaCsvPath;
    /**
     * Ruta temporal del ZIP de auditoría: varios CSV de hasta
     * {@code AuditoriaService.FILAS_POR_ARCHIVO} filas cada uno, nombrados
     * {@code <jobId>-<inicio>-<fin>.csv}. Es el formato de descarga actual.
     */
    public volatile Path auditoriaZipPath;
    /** Tamaño en filas de la auditoría (sin contar la cabecera). */
    public volatile int auditoriaFilas;

    public final LocalDateTime inicio = LocalDateTime.now();
    public volatile LocalDateTime fin;

    /** Motor utilizado: "alns" o "aco". */
    public volatile String algoritmo = "alns";

    /**
     * Seed efectivo de la corrida. Si el cliente no pasa uno, se genera uno aleatorio
     * y se reporta aquí — cualquier valor reportado garantiza reproducibilidad si se
     * vuelve a invocar con el mismo seed, motor y k.
     */
    public volatile long seed = 0L;

    /**
     * Fecha de inicio efectiva del escenario 2 (puede ser distinta de la primera
     * ventana del dataset si el cliente la fijó en el endpoint). Null = primera ventana.
     */
    public volatile LocalDateTime fechaInicio;

    // ── Parámetros reproducibles para el reinicio (ver PlanificadorService.reiniciarJob) ──
    // Junto con escenario/k/algoritmo/seed/fechaInicio permiten re-lanzar una corrida idéntica.
    // Los overrides de E2 y el umbral de E3 antes solo vivían en el EjecucionParams del arranque.
    /** E2: override de Sa (minutos). Null = default del yaml. */
    public volatile Integer saMin;
    /** E2: override de Ta (segundos). Null = default del yaml. */
    public volatile Integer taSegundos;
    /** E2: duración en días. Null = default (max-ventanas global). */
    public volatile Integer dias;
    /** E2: warm-up previo (hoy forzado a false en el controller). */
    public volatile boolean procesamientoPrevio = false;
    /** E3: umbral de colapso usado en el arranque. Null en E1/E2. */
    public volatile Double umbralColapso;

    /**
     * Bloques publicados conforme van saliendo del motor (modo Sa/Ta).
     *
     * <p>El front consulta {@code GET /jobs/{jobId}/bloques?desde=N} para
     * dibujar la simulación en tiempo real durante el sleep {@code Sa - Ta}.
     * Se llena vía {@link #publicarBloque}. {@link CopyOnWriteArrayList}
     * garantiza lectura concurrente sin bloquear al worker.
     */
    private final List<BloqueSimulacion> bloquesParciales =
            new CopyOnWriteArrayList<>();

    public JobState(String jobId, String escenario, int k) {
        this.jobId     = jobId;
        this.escenario = escenario;
        this.k         = k;
    }

    public double getProgreso() {
        return totalBloques == 0 ? 0.0 : (double) bloqueActual / totalBloques;
    }

    /** Progreso del warm-up [0..1]. 0 si no hay warm-up para este job. */
    public double getProgresoWarmup() {
        return totalBloquesWarmup == 0 ? 0.0 : (double) bloqueWarmup / totalBloquesWarmup;
    }

    /**
     * Órdenes de cancelación de vuelo pendientes de aplicar, enviadas por el usuario EN VIVO. El
     * worker del job las drena al inicio de cada bloque (ver
     * {@code PlanificadorService.aplicarCancelacionesVuelo}). {@link ConcurrentLinkedQueue} permite
     * que el endpoint REST encole desde otro hilo sin bloquear al worker.
     */
    private final Queue<CancelacionVueloRequest> cancelacionesVueloPendientes =
            new ConcurrentLinkedQueue<>();

    /** Encola una orden de cancelación de vuelo para que el worker la aplique en el próximo bloque. */
    public void encolarCancelacionVuelo(CancelacionVueloRequest orden) {
        if (orden != null) cancelacionesVueloPendientes.add(orden);
    }

    /**
     * Cancelaciones de vuelo YA APLICADAS por el worker (con sus envíos afectados), en orden de
     * aplicación. La expone {@code GET /jobs/{id}/estado} para que el front sepa qué vuelo-días
     * dejaron de existir y deje de animarlos — antes solo eran visibles en el CSV de auditoría
     * final. {@code fechaHoraSalida} está en la hora local del vuelo (el mismo eje que el request
     * de cancelación). El worker escribe y el front lee concurrentemente: CopyOnWriteArrayList.
     */
    private final List<VueloCancelado> vuelosCancelados = new CopyOnWriteArrayList<>();

    /** Cola de cancelaciones de vuelo pendientes (la drena el worker del job). */
    public Queue<CancelacionVueloRequest> getCancelacionesVueloPendientes() {
        return cancelacionesVueloPendientes;
    }

    /**
     * Series de ocupación de almacén por SLOT de 60 min, una entrada por bloque publicado (mismo
     * orden e índices que {@link #bloquesParciales}). El front las consume con
     * {@code GET /jobs/{id}/almacenes/serie?desde=N} para actualizar EN VIVO las maletas de cada
     * almacén mientras su reloj de animación recorre el bloque.
     */
    private final List<List<OcupacionAlmacenSlot>> seriesAlmacenes =
            new CopyOnWriteArrayList<>();

    /** Publica un bloque procesado para que el front lo consuma incrementalmente. */
    public void publicarBloque(BloqueSimulacion bloque) {
        if (bloque != null) bloquesParciales.add(bloque);
    }

    /** Publica la serie por slots del bloque recién publicado (mantener 1:1 con publicarBloque). */
    public void publicarSerieAlmacenes(List<OcupacionAlmacenSlot> serie) {
        seriesAlmacenes.add(serie != null ? serie : List.of());
    }

    /** Series publicadas desde {@code desde} (inclusive), alineadas con los índices de bloque. */
    public List<List<OcupacionAlmacenSlot>> seriesDesde(int desde) {
        int n = seriesAlmacenes.size();
        if (desde < 0) desde = 0;
        if (desde >= n) return List.of();
        return List.copyOf(seriesAlmacenes.subList(desde, n));
    }

    public int seriesPublicadas() {
        return seriesAlmacenes.size();
    }

    /** Devuelve los bloques publicados desde {@code desde} (inclusive). */
    public List<BloqueSimulacion> bloquesDesde(int desde) {
        int n = bloquesParciales.size();
        if (desde < 0) desde = 0;
        if (desde >= n) return List.of();
        return List.copyOf(bloquesParciales.subList(desde, n));
    }

    public int bloquesPublicados() {
        return bloquesParciales.size();
    }
}
