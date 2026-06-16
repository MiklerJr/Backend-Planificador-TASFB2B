package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fase 5a — Persiste la solución (rutas) de cada bloque en {@code ruta_asignada}/{@code tramo_ruta}.
 *
 * <p><b>Serializado:</b> como el esquema no tiene {@code corrida_id} (decisión de diseño: una corrida
 * a la vez), solo un job escribe a la vez. El primero que arranca toma la persistencia y limpia las
 * tablas; los jobs concurrentes corren solo en memoria.
 *
 * <p><b>Aditivo y best-effort:</b> no toca el motor ni la memoria del job. Si una escritura falla,
 * se loguea y NO se propaga (la simulación no debe caerse por un problema de persistencia).
 */
@Slf4j
@Service
public class PersistenciaSolucionService {

    private final JdbcTemplate jdbc;

    /**
     * Plantilla transaccional para escribir cada bloque como unidad atómica (rollback si fallan los
     * tramos ⇒ no quedan rutas sin tramos). {@code null} solo en el constructor no-op de tests, donde
     * nunca se llega a persistir (cae el camino directo sin transacción).
     */
    private final TransactionTemplate tx;

    /** jobId de la corrida que tiene tomada la persistencia. null = libre. */
    private final AtomicReference<String> corridaActiva = new AtomicReference<>();

    /** Tamaño de lote para los INSERT multi-fila de {@code ruta_asignada} (acota nº de parámetros). */
    private static final int LOTE_RUTAS = 500;

    public PersistenciaSolucionService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = (txManager != null) ? new TransactionTemplate(txManager) : null;
    }

    /**
     * Intenta tomar la persistencia para {@code jobId}. Si la toma (no hay otra corrida activa),
     * vacía las tablas de solución ({@code TRUNCATE}) y resetea {@code envio.estado}, y devuelve
     * {@code true}. Si ya hay otra corrida persistiendo, devuelve {@code false} (ese job corre solo
     * en memoria).
     */
    public boolean iniciarCorrida(String jobId) {
        if (jobId == null) return false;
        if (!corridaActiva.compareAndSet(null, jobId)) {
            log.warn("Persistencia ocupada por la corrida {}; el job {} NO persistirá (solo memoria).",
                    corridaActiva.get(), jobId);
            return false;
        }
        try {
            jdbc.execute("TRUNCATE ruta_asignada, tramo_ruta, cancelacion_vuelo RESTART IDENTITY CASCADE");
            jdbc.update("UPDATE envio SET estado = 'pendiente'");
            log.info("Persistencia iniciada para la corrida {} (tablas de solución limpias).", jobId);
            return true;
        } catch (Exception e) {
            log.error("No se pudo limpiar al iniciar la corrida {}: {}", jobId, e.getMessage());
            corridaActiva.compareAndSet(jobId, null);
            return false;
        }
    }

    /** Libera la persistencia si la tenía {@code jobId}. Llamar en {@code finally} al terminar. */
    public void finalizarCorrida(String jobId) {
        if (jobId != null && corridaActiva.compareAndSet(jobId, null)) {
            log.info("Persistencia liberada por la corrida {}.", jobId);
        }
    }

    /** ¿Es {@code jobId} la corrida que está persistiendo ahora mismo? */
    public boolean persiste(String jobId) {
        return jobId != null && jobId.equals(corridaActiva.get());
    }

    /**
     * Persiste las rutas de los batches ENRUTADOS del bloque (solo si {@code jobId} es la corrida
     * activa). Mantiene el histórico 1:N: desactiva la ruta activa previa del envío e inserta la
     * nueva como {@code activa}. Best-effort: si falla, loguea y no propaga.
     */
    public void persistirBloque(String jobId, List<LuggageBatch> batches) {
        if (batches == null || batches.isEmpty() || !persiste(jobId)) return;

        List<LuggageBatch> enrutados = new ArrayList<>();
        for (LuggageBatch b : batches) {
            if (b == null || b.getId() == null) continue;
            List<Edge> ruta = b.getAssignedRoute();
            List<Long> deps = b.getAssignedDepartures();
            if (ruta != null && !ruta.isEmpty() && deps != null && deps.size() == ruta.size()) {
                enrutados.add(b);
            }
        }
        if (enrutados.isEmpty()) return;

        try {
            // Los 3 pasos del bloque se escriben como unidad atómica: si los tramos fallan (p. ej. FK),
            // se revierten también las rutas, evitando rutas activas sin tramos.
            if (tx != null) {
                tx.executeWithoutResult(status -> escribirBloque(enrutados));
            } else {
                escribirBloque(enrutados);
            }
        } catch (Exception e) {
            log.error("Persistencia del bloque falló (corrida {}): {}", jobId, e.getMessage());
        }
    }

    /** Escribe el bloque (debe correr dentro de una transacción): desactiva previa → rutas → tramos. */
    private void escribirBloque(List<LuggageBatch> enrutados) {
        // 1. Desactivar la ruta activa previa de estos envíos (no-op para los nuevos).
        List<Object[]> desactivar = new ArrayList<>(enrutados.size());
        for (LuggageBatch b : enrutados) desactivar.add(new Object[]{ b.getId() });
        jdbc.batchUpdate("UPDATE ruta_asignada SET activa = FALSE WHERE id_envio = ? AND activa", desactivar);

        // 2. Insertar las rutas nuevas (activa=true) por lotes, recuperando id_ruta por id_envio.
        Map<String, Long> idRutaPorEnvio = new HashMap<>();
        for (int i = 0; i < enrutados.size(); i += LOTE_RUTAS) {
            insertarRutasLote(enrutados.subList(i, Math.min(i + LOTE_RUTAS, enrutados.size())), idRutaPorEnvio);
        }

        // 3. Insertar todos los tramos del bloque en un único batch.
        List<Object[]> tramos = new ArrayList<>();
        for (LuggageBatch b : enrutados) {
            Long idRuta = idRutaPorEnvio.get(b.getId());
            if (idRuta == null) continue;
            List<Edge> ruta = b.getAssignedRoute();
            List<Long> deps = b.getAssignedDepartures();
            for (int ti = 0; ti < ruta.size(); ti++) {
                Edge e = ruta.get(ti);
                long depMin = deps.get(ti);
                long arrMin = depMin + e.durationMinutes;
                tramos.add(new Object[]{
                        idRuta, ti, normalizarIdVuelo(e.id),
                        epochMinToLdt(depMin), epochMinToLdt(arrMin)
                });
            }
        }
        jdbc.batchUpdate("INSERT INTO tramo_ruta (id_ruta, numero_orden, id_vuelo, hora_salida_utc, hora_llegada_utc) "
                + "VALUES (?, ?, ?, ?, ?)", tramos);
    }

    /** INSERT multi-fila de un lote de rutas con {@code RETURNING} para mapear id_envio → id_ruta. */
    private void insertarRutasLote(List<LuggageBatch> lote, Map<String, Long> idRutaPorEnvio) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO ruta_asignada (id_envio, activa, costo_total, duracion_horas, cumple_sla, slack_sla_min, llegada_utc) VALUES ");
        List<Object> args = new ArrayList<>(lote.size() * 6);
        for (int i = 0; i < lote.size(); i++) {
            LuggageBatch b = lote.get(i);
            if (i > 0) sql.append(',');
            sql.append("(?, TRUE, ?, ?, ?, ?, ?)");

            double transitMin = b.getTotalTransitTimeMins();
            double slackMin = b.getSlaLimitHours() * 60.0 - transitMin;
            List<Edge> ruta = b.getAssignedRoute();
            List<Long> deps = b.getAssignedDepartures();
            long llegadaMin = deps.get(deps.size() - 1) + ruta.get(ruta.size() - 1).durationMinutes;

            args.add(b.getId());
            args.add(transitMin);          // costo_total (proxy = tránsito total en min)
            args.add(transitMin / 60.0);   // duracion_horas
            args.add(b.isCumpleSLA());
            args.add((int) Math.round(slackMin));
            args.add(epochMinToLdt(llegadaMin));
        }
        sql.append(" RETURNING id_ruta, id_envio");
        jdbc.query(sql.toString(),
                (RowCallbackHandler) rs -> idRutaPorEnvio.put(rs.getString("id_envio"), rs.getLong("id_ruta")),
                args.toArray());
    }

    /** El motor genera el id como {@code ICAO-ICAO-HH:MM}; {@code vuelo.id_vuelo} es {@code ICAO-ICAO-HHMM}. */
    static String normalizarIdVuelo(String edgeId) {
        return edgeId == null ? null : edgeId.replace(":", "");
    }

    /** Inversa de {@code PlanificadorService.toEpochMin}: epoch-min UTC → LocalDateTime (columna TIMESTAMP). */
    static LocalDateTime epochMinToLdt(long epochMin) {
        long day = Math.floorDiv(epochMin, 1440L);
        int minOfDay = (int) Math.floorMod(epochMin, 1440L);
        return LocalDate.ofEpochDay(day).atTime(minOfDay / 60, minOfDay % 60);
    }
}
