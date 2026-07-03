package com.tasfb2b.planificador.servicios.persistencia;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.dto.trabajos.EnvioInyectadoInfo;
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

@Slf4j
@Service
public class PersistenciaSolucionService {

    private final JdbcTemplate jdbc;

    private final TransactionTemplate tx;

    private final AtomicReference<String> corridaActiva = new AtomicReference<>();

    private volatile String corridaPersistidaEnBd;

    private static final int LOTE_RUTAS = 500;

    public PersistenciaSolucionService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = (txManager != null) ? new TransactionTemplate(txManager) : null;
    }

    public boolean iniciarCorrida(String jobId) {
        if (jobId == null) return false;
        if (!corridaActiva.compareAndSet(null, jobId)) {
            log.warn("Persistencia ocupada por la corrida {}; el job {} NO persistirá (solo memoria).",
                    corridaActiva.get(), jobId);
            return false;
        }
        try {
            jdbc.execute("TRUNCATE ruta_asignada, tramo_ruta, cancelacion_vuelo, envio_inyectado, "
                    + "ruta_inyectada, tramo_inyectado RESTART IDENTITY CASCADE");
            corridaPersistidaEnBd = jobId;   // a partir de aquí la BD refleja la solución de este job
            log.info("Persistencia iniciada para la corrida {} (tablas de solución limpias).", jobId);
            return true;
        } catch (Exception e) {
            log.error("No se pudo limpiar al iniciar la corrida {}: {}", jobId, e.getMessage());
            corridaActiva.compareAndSet(jobId, null);
            return false;
        }
    }

    public boolean tomarParaLectura(String jobId) {
        if (jobId == null || !jobId.equals(corridaPersistidaEnBd)) return false;
        return corridaActiva.compareAndSet(null, jobId);
    }

    public void finalizarCorrida(String jobId) {
        if (jobId != null && corridaActiva.compareAndSet(jobId, null)) {
            log.info("Persistencia liberada por la corrida {}.", jobId);
        }
    }

    public boolean persiste(String jobId) {
        return jobId != null && jobId.equals(corridaActiva.get());
    }

    public boolean reflejaEnBd(String jobId) {
        return jobId != null && jobId.equals(corridaPersistidaEnBd);
    }

    public void persistirBloque(String jobId, List<LoteEnvio> batches) {
        if (batches == null || batches.isEmpty() || !persiste(jobId)) return;

        List<LoteEnvio> enrutados = new ArrayList<>();
        List<LoteEnvio> inyectados = new ArrayList<>();
        for (LoteEnvio b : batches) {
            if (b == null || b.getId() == null) continue;
            List<Arista> ruta = b.getRutaCompleta();
            List<Long> deps = b.getDeparturesCompletas();
            if (ruta == null || ruta.isEmpty() || deps == null || deps.size() != ruta.size()) continue;
            (b.isSintetico() ? inyectados : enrutados).add(b);
        }
        if (enrutados.isEmpty() && inyectados.isEmpty()) return;

        try {
            Runnable escribir = () -> {
                if (!enrutados.isEmpty()) escribirBloque(enrutados, TablasSolucion.DATASET);
                if (!inyectados.isEmpty()) escribirBloque(inyectados, TablasSolucion.INYECTADO);
            };
            if (tx != null) {
                tx.executeWithoutResult(status -> escribir.run());
            } else {
                escribir.run();
            }
        } catch (Exception e) {
            log.error("Persistencia del bloque falló (corrida {}): {}", jobId, e.getMessage());
        }
    }

    private record TablasSolucion(String rutaTabla, String tramoTabla, String idRutaCol) {
        static final TablasSolucion DATASET   = new TablasSolucion("ruta_asignada", "tramo_ruta", "id_ruta");
        static final TablasSolucion INYECTADO = new TablasSolucion("ruta_inyectada", "tramo_inyectado", "id_ruta_iny");
    }

    public void persistirInyecciones(String jobId, List<EnvioInyectadoInfo> items) {
        if (items == null || items.isEmpty() || !persiste(jobId)) return;
        try {
            List<Object[]> args = new ArrayList<>(items.size());
            for (EnvioInyectadoInfo it : items) {
                args.add(new Object[]{
                        it.getIdEnvio(), it.getOrigen(), it.getDestino(), it.getCantidad(),
                        it.getClienteId(), LocalDateTime.parse(it.getReadyTimeUtc()),
                        it.getSlaHoras(), it.getBloqueIdx(), it.getRegistrador(), it.getSede() });
            }
            jdbc.batchUpdate("INSERT INTO envio_inyectado (id_envio, icao_origen, icao_destino, "
                    + "cantidad_maletas, id_cliente, ready_time_utc, sla_horas, bloque_idx, "
                    + "registrador, sede) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", args);
        } catch (Exception e) {
            log.error("Persistencia de inyecciones falló (corrida {}): {}", jobId, e.getMessage());
        }
    }

    public void persistirCancelaciones(String jobId, List<CancelacionVueloDb> nuevas) {
        if (nuevas == null || nuevas.isEmpty() || !persiste(jobId)) return;
        try {
            List<Object[]> args = new ArrayList<>(nuevas.size());
            for (CancelacionVueloDb c : nuevas) {
                args.add(new Object[]{ c.idVuelo(), c.fecha(), c.enviosAfectados() });
            }
            jdbc.batchUpdate("INSERT INTO cancelacion_vuelo (id_vuelo, fecha_cancelacion, envios_afectados) "
                    + "VALUES (?, ?, ?)", args);
        } catch (Exception e) {
            log.error("Persistencia de cancelaciones falló (corrida {}): {}", jobId, e.getMessage());
        }
    }

    public record CancelacionVueloDb(String idVuelo, LocalDate fecha, int enviosAfectados) {}

    private void escribirBloque(List<LoteEnvio> enrutados, TablasSolucion t) {
        // 1. Desactivar la ruta activa previa de estos envíos (no-op para los nuevos).
        List<Object[]> desactivar = new ArrayList<>(enrutados.size());
        for (LoteEnvio b : enrutados) desactivar.add(new Object[]{ b.getId() });
        jdbc.batchUpdate("UPDATE " + t.rutaTabla() + " SET activa = FALSE WHERE id_envio = ? AND activa", desactivar);

        // 2. Insertar las rutas nuevas (activa=true) por lotes, recuperando id_ruta por id_envio.
        Map<String, Long> idRutaPorEnvio = new HashMap<>();
        for (int i = 0; i < enrutados.size(); i += LOTE_RUTAS) {
            insertarRutasLote(enrutados.subList(i, Math.min(i + LOTE_RUTAS, enrutados.size())), idRutaPorEnvio, t);
        }

        // 3. Insertar todos los tramos del bloque en un único batch.
        List<Object[]> tramos = new ArrayList<>();
        for (LoteEnvio b : enrutados) {
            Long idRuta = idRutaPorEnvio.get(b.getId());
            if (idRuta == null) continue;
            List<Arista> ruta = b.getRutaCompleta();
            List<Long> deps = b.getDeparturesCompletas();
            for (int ti = 0; ti < ruta.size(); ti++) {
                Arista e = ruta.get(ti);
                long depMin = deps.get(ti);
                long arrMin = depMin + e.durationMinutes;
                tramos.add(new Object[]{
                        idRuta, ti, normalizarIdVuelo(e.id),
                        epochMinToLdt(depMin), epochMinToLdt(arrMin)
                });
            }
        }
        jdbc.batchUpdate("INSERT INTO " + t.tramoTabla() + " (" + t.idRutaCol()
                + ", numero_orden, id_vuelo, hora_salida_utc, hora_llegada_utc) "
                + "VALUES (?, ?, ?, ?, ?)", tramos);
    }

    private void insertarRutasLote(List<LoteEnvio> lote, Map<String, Long> idRutaPorEnvio, TablasSolucion t) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO " + t.rutaTabla() + " (id_envio, activa, costo_total, duracion_horas, cumple_sla, slack_sla_min, llegada_utc) VALUES ");
        List<Object> args = new ArrayList<>(lote.size() * 6);
        for (int i = 0; i < lote.size(); i++) {
            LoteEnvio b = lote.get(i);
            if (i > 0) sql.append(',');
            sql.append("(?, TRUE, ?, ?, ?, ?, ?)");

            List<Arista> ruta = b.getRutaCompleta();
            List<Long> deps = b.getDeparturesCompletas();
            long readyMin = OperadorReparacionVoraz.toEpochMinPublic(b.getReadyTime());
            long llegadaMin = deps.get(deps.size() - 1) + ruta.get(ruta.size() - 1).durationMinutes;
            double transitMin = llegadaMin - readyMin;
            double slackMin = b.getSlaLimitHours() * 60.0 - transitMin;

            args.add(b.getId());
            args.add(transitMin);          // costo_total (proxy = tránsito total en min)
            args.add(transitMin / 60.0);   // duracion_horas
            args.add(b.isCumpleSLA());
            args.add((int) Math.round(slackMin));
            args.add(epochMinToLdt(llegadaMin));
        }
        sql.append(" RETURNING ").append(t.idRutaCol()).append(" AS id_ruta, id_envio");
        jdbc.query(sql.toString(),
                (RowCallbackHandler) rs -> idRutaPorEnvio.put(rs.getString("id_envio"), rs.getLong("id_ruta")),
                args.toArray());
    }

    public static String normalizarIdVuelo(String edgeId) {
        return edgeId == null ? null : edgeId.replace(":", "");
    }

    static LocalDateTime epochMinToLdt(long epochMin) {
        long day = Math.floorDiv(epochMin, 1440L);
        int minOfDay = (int) Math.floorMod(epochMin, 1440L);
        return LocalDate.ofEpochDay(day).atTime(minOfDay / 60, minOfDay % 60);
    }
}
