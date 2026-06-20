package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.dto.VueloCancelado;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.TipoEnvio;
import com.tasfb2b.planificador.util.DataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Fase 5b — Lee la solución persistida (Fase 5a) y reconstruye {@link LuggageBatch} desde BD, para
 * dejar de retener los batches enrutados en RAM.
 *
 * <p>Dos usos:
 * <ul>
 *   <li><b>ZIP de auditoría</b> ({@link #forEachEnrutado}): recorre TODOS los envíos enrutados en
 *       streaming (cursor JDBC), ordenados por {@code readyTime} UTC, sin cargar todo a memoria.</li>
 *   <li><b>Cancelación en vivo</b> ({@link #afectadosPorVuelo}): los envíos cuya ruta activa usa un
 *       vuelo-día cancelado, reconstruidos con su ruta para devolverlos al backlog.</li>
 * </ul>
 *
 * <p>La ruta se reconstruye mapeando {@code tramo_ruta.id_vuelo} a un {@link Edge} del grafo vía el
 * índice {@link #construirIndiceVuelo}. El {@code readyTime} UTC se deriva igual que
 * {@code AlgorithmMapper.mapToBatches}: {@code fecha_hora_registro − offset(origen)}; el SLA, con
 * {@code TipoEnvio.derivar}.
 */
@Slf4j
@Service
public class SolucionBdReader {

    private final JdbcTemplate jdbc;
    private final DataLoader dataLoader;
    /**
     * Transacción READ-ONLY para el streaming del ZIP: el cursor server-side de PostgreSQL
     * (fetchSize) solo surte efecto con autoCommit=false; sin él, el driver cargaría TODO el
     * ResultSet en RAM (millones de filas). {@code null} en el constructor no-op de tests.
     */
    private final TransactionTemplate txReadOnly;

    public SolucionBdReader(JdbcTemplate jdbc, DataLoader dataLoader, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.dataLoader = dataLoader;
        if (txManager != null) {
            this.txReadOnly = new TransactionTemplate(txManager);
            this.txReadOnly.setReadOnly(true);
        } else {
            this.txReadOnly = null;
        }
    }

    /** Índice {@code id_vuelo (ICAO-ICAO-HHMM) → Edge} del grafo (una vez por corrida; ~2.866 entradas). */
    public Map<String, Edge> construirIndiceVuelo(Graph graph) {
        Map<String, Edge> idx = new HashMap<>();
        if (graph == null) return idx;
        for (Edge e : graph.edges) {
            idx.put(PersistenciaSolucionService.normalizarIdVuelo(e.id), e);
        }
        return idx;
    }

    /**
     * Recorre en streaming los envíos con ruta activa, reconstruidos como {@link LuggageBatch}
     * (con su ruta y departures), ordenados por {@code readyTime} UTC. Pensado para alimentar el
     * ZIP de auditoría sin retener nada O(envíos) en RAM.
     */
    public void forEachEnrutado(Map<String, Edge> indiceVuelo, Consumer<LuggageBatch> consumer) {
        // Orden por readyTime UTC = registro − offset(origen); agrupa los tramos de cada ruta
        // contiguos (mismo id_ruta ⇒ mismo readyTime) para poder emitir un batch a la vez.
        String sql =
                "SELECT r.id_ruta, r.id_envio, r.cumple_sla, "
              + "       e.icao_origen, e.icao_destino, e.cantidad_maletas, e.fecha_hora_registro, "
              + "       t.numero_orden, t.id_vuelo, t.hora_salida_utc "
              + "FROM ruta_asignada r "
              + "JOIN envio e ON e.id_envio = r.id_envio "
              + "JOIN aeropuerto a ON a.icao = e.icao_origen "
              + "JOIN tramo_ruta t ON t.id_ruta = r.id_ruta "
              + "WHERE r.activa "
              + "ORDER BY (e.fecha_hora_registro - make_interval(hours => a.huso_horario)), r.id_ruta, t.numero_orden";

        final long[] idRutaActual = { Long.MIN_VALUE };
        final Acumulador acc = new Acumulador();
        org.springframework.jdbc.core.RowCallbackHandler rch = rs -> {
            long idRuta = rs.getLong("id_ruta");
            if (idRuta != idRutaActual[0]) {
                emitir(acc, indiceVuelo, consumer);   // cierra el batch anterior
                idRutaActual[0] = idRuta;
                acc.reset(rs);
            }
            acc.tramos.add(new Tramo(rs.getInt("numero_orden"), rs.getString("id_vuelo"),
                    rs.getTimestamp("hora_salida_utc").toLocalDateTime()));
        };
        // Cursor server-side (fetchSize por-statement) dentro de una tx read-only ⇒ autoCommit=false.
        Runnable lectura = () -> jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            ps.setFetchSize(2000);
            return ps;
        }, rch);
        if (txReadOnly != null) txReadOnly.executeWithoutResult(st -> lectura.run());
        else lectura.run();
        emitir(acc, indiceVuelo, consumer);            // último batch pendiente
    }

    /**
     * Envíos cuya ruta ACTIVA usa el vuelo {@code idVueloNormalizado} en el día {@code dia} (UTC),
     * reconstruidos con su ruta vieja para que {@code procesarBloque} libere su capacidad al
     * sacarlos del backlog. {@code idsVueloNormalizado} cubre los varios edge-día de la cancelación.
     */
    public List<LuggageBatch> afectadosPorVuelo(List<String> idsVueloNormalizado, LocalDate dia,
                                                Map<String, Edge> indiceVuelo) {
        List<LuggageBatch> out = new ArrayList<>();
        if (idsVueloNormalizado == null || idsVueloNormalizado.isEmpty() || dia == null) return out;

        // 1. id_ruta afectados (ruta activa que usa alguno de los vuelos en ese día UTC).
        String sqlIds =
                "SELECT DISTINCT t.id_ruta FROM tramo_ruta t "
              + "JOIN ruta_asignada r ON r.id_ruta = t.id_ruta "
              + "WHERE r.activa AND t.id_vuelo = ANY(?) AND CAST(t.hora_salida_utc AS DATE) = ?";
        List<Long> idsRuta = jdbc.query(sqlIds,
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("varchar", idsVueloNormalizado.toArray()));
                    ps.setObject(2, dia);
                },
                (rs, n) -> rs.getLong("id_ruta"));
        if (idsRuta.isEmpty()) return out;

        // 2. Reconstruir cada ruta afectada (datos del envío + todos sus tramos).
        String sqlRutas =
                "SELECT r.id_ruta, r.id_envio, r.cumple_sla, "
              + "       e.icao_origen, e.icao_destino, e.cantidad_maletas, e.fecha_hora_registro, "
              + "       t.numero_orden, t.id_vuelo, t.hora_salida_utc "
              + "FROM ruta_asignada r "
              + "JOIN envio e ON e.id_envio = r.id_envio "
              + "JOIN tramo_ruta t ON t.id_ruta = r.id_ruta "
              + "WHERE r.id_ruta = ANY(?) "
              + "ORDER BY r.id_ruta, t.numero_orden";
        final long[] idRutaActual = { Long.MIN_VALUE };
        final Acumulador acc = new Acumulador();
        jdbc.query(con -> {
            var ps = con.prepareStatement(sqlRutas);
            ps.setArray(1, con.createArrayOf("bigint", idsRuta.toArray()));
            return ps;
        }, rs -> {
            long idRuta = rs.getLong("id_ruta");
            if (idRuta != idRutaActual[0]) {
                emitir(acc, indiceVuelo, out::add);
                idRutaActual[0] = idRuta;
                acc.reset(rs);
            }
            acc.tramos.add(new Tramo(rs.getInt("numero_orden"), rs.getString("id_vuelo"),
                    rs.getTimestamp("hora_salida_utc").toLocalDateTime()));
        });
        emitir(acc, indiceVuelo, out::add);
        return out;
    }

    /**
     * Lee las cancelaciones de vuelo persistidas en {@code cancelacion_vuelo} y las reconstruye como
     * {@link VueloCancelado} para el CSV de auditoría (fuente de verdad en BD, no la lista en RAM).
     * {@code origen}/{@code destino}/hora-salida UTC salen del {@code Edge} del índice (sin parsear
     * strings); {@code enviosAfectados} de la propia fila. Orden estable por fecha + id_vuelo. Si un
     * {@code id_vuelo} no estuviera en el índice (defensivo), deriva origen/destino/hora del propio id.
     */
    public List<VueloCancelado> leerCancelaciones(Map<String, Edge> indiceVuelo) {
        String sql = "SELECT id_vuelo, fecha_cancelacion, envios_afectados FROM cancelacion_vuelo "
                   + "ORDER BY fecha_cancelacion, id_vuelo";
        return jdbc.query(sql, (rs, n) -> {
            String idVuelo = rs.getString("id_vuelo");
            LocalDate fecha = rs.getObject("fecha_cancelacion", LocalDate.class);
            int afectados = rs.getInt("envios_afectados");
            Edge e = indiceVuelo != null ? indiceVuelo.get(idVuelo) : null;
            String origen, destino;
            int depMin;
            if (e != null) {
                origen = e.from.code;
                destino = e.to.code;
                depMin = e.depMinuteOfDay;
            } else {
                // Fallback defensivo: derivar del propio id_vuelo (ICAO-ICAO-HHMM).
                String[] partes = idVuelo != null ? idVuelo.split("-") : new String[0];
                origen = partes.length > 0 ? partes[0] : "";
                destino = partes.length > 1 ? partes[1] : "";
                depMin = partes.length > 2 ? parseHHMM(partes[2]) : 0;
            }
            LocalDateTime salidaUtc = fecha.atStartOfDay().plusMinutes(depMin);
            return new VueloCancelado(origen, destino, salidaUtc, afectados);
        });
    }

    /** {@code "HHMM"} → minutos del día; 0 si no parsea (fallback de {@link #leerCancelaciones}). */
    private static int parseHHMM(String hhmm) {
        try {
            int v = Integer.parseInt(hhmm.trim());
            return (v / 100) * 60 + (v % 100);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Reconstruye el envío {@code idEnvio} desde su ruta ACTIVA en BD, o {@link Optional#empty()} si
     * no tiene ruta activa persistida (no existe, o quedó en backlog/sin ruta). Pensado para la
     * consulta puntual del front del detalle de un envío de un bloque anterior (ya purgado de la RAM
     * del job). Mismo patrón de reconstrucción que {@link #afectadosPorVuelo}, pero para un único
     * {@code id_envio}: el índice parcial {@code ux_ruta_activa_por_envio} garantiza una sola ruta
     * activa, así que todos los tramos pertenecen a la misma.
     */
    public Optional<LuggageBatch> buscarPorEnvio(String idEnvio, Map<String, Edge> indiceVuelo) {
        if (idEnvio == null || idEnvio.isBlank()) return Optional.empty();
        String sql =
                "SELECT r.id_ruta, r.id_envio, r.cumple_sla, "
              + "       e.icao_origen, e.icao_destino, e.cantidad_maletas, e.fecha_hora_registro, "
              + "       t.numero_orden, t.id_vuelo, t.hora_salida_utc "
              + "FROM ruta_asignada r "
              + "JOIN envio e ON e.id_envio = r.id_envio "
              + "JOIN tramo_ruta t ON t.id_ruta = r.id_ruta "
              + "WHERE r.id_envio = ? AND r.activa "
              + "ORDER BY t.numero_orden";
        final Acumulador acc = new Acumulador();
        jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            ps.setString(1, idEnvio);
            return ps;
        }, rs -> {
            if (acc.idEnvio == null) acc.reset(rs);   // datos del envío: del primer tramo
            acc.tramos.add(new Tramo(rs.getInt("numero_orden"), rs.getString("id_vuelo"),
                    rs.getTimestamp("hora_salida_utc").toLocalDateTime()));
        });
        if (acc.idEnvio == null || acc.tramos.isEmpty()) return Optional.empty();
        return Optional.ofNullable(reconstruir(acc, indiceVuelo));
    }

    // ── Reconstrucción ──────────────────────────────────────────────────────

    /** Cierra el batch acumulado (si hay) y lo entrega al consumidor. */
    private void emitir(Acumulador acc, Map<String, Edge> indiceVuelo, Consumer<LuggageBatch> consumer) {
        if (acc.idEnvio == null || acc.tramos.isEmpty()) return;
        LuggageBatch b = reconstruir(acc, indiceVuelo);
        if (b != null) consumer.accept(b);
        acc.idEnvio = null;
        acc.tramos.clear();
    }

    private LuggageBatch reconstruir(Acumulador acc, Map<String, Edge> indiceVuelo) {
        Aeropuerto origen = dataLoader.getAeropuerto(acc.origen);
        Aeropuerto destino = dataLoader.getAeropuerto(acc.destino);
        if (origen == null || destino == null || origen.getOffsetHorario() == null) {
            log.warn("No se pudo reconstruir el envío {} (aeropuerto/offset ausente)", acc.idEnvio);
            return null;
        }
        LocalDateTime readyUtc = acc.registroLocal.minusHours(origen.getOffsetHorario());
        int sla = TipoEnvio.derivar(origen, destino) == TipoEnvio.INTRACONTINENTAL ? 24 : 48;

        LuggageBatch b = new LuggageBatch(acc.idEnvio, acc.cantidad, sla,
                origen.getCodigo(), destino.getCodigo(), readyUtc);

        acc.tramos.sort((x, y) -> Integer.compare(x.numeroOrden, y.numeroOrden));
        List<Edge> ruta = new ArrayList<>(acc.tramos.size());
        List<Long> deps = new ArrayList<>(acc.tramos.size());
        for (Tramo t : acc.tramos) {
            Edge e = indiceVuelo.get(t.idVuelo);
            if (e == null) {
                log.warn("Tramo con id_vuelo {} sin Edge en el grafo (envío {}); ruta descartada",
                        t.idVuelo, acc.idEnvio);
                return null;
            }
            ruta.add(e);
            deps.add(ldtToEpochMin(t.horaSalidaUtc));
        }
        b.setAssignedRoute(ruta);
        b.setAssignedDepartures(deps);
        b.setCumpleSLA(acc.cumpleSla);
        return b;
    }

    /** Inversa de {@code PersistenciaSolucionService.epochMinToLdt}: LocalDateTime UTC → epoch-min. */
    static long ldtToEpochMin(LocalDateTime dt) {
        return dt.toLocalDate().toEpochDay() * 1440L + dt.getHour() * 60L + dt.getMinute();
    }

    /** Estado mutable mientras se agrupan los tramos de un mismo {@code id_ruta} en streaming. */
    private static final class Acumulador {
        String idEnvio;
        String origen;
        String destino;
        int cantidad;
        LocalDateTime registroLocal;
        boolean cumpleSla;
        final List<Tramo> tramos = new ArrayList<>();

        void reset(ResultSet rs) {
            try {
                idEnvio = rs.getString("id_envio");
                origen = rs.getString("icao_origen");
                destino = rs.getString("icao_destino");
                cantidad = rs.getInt("cantidad_maletas");
                registroLocal = rs.getTimestamp("fecha_hora_registro").toLocalDateTime();
                cumpleSla = rs.getBoolean("cumple_sla");
                tramos.clear();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Lectura de envío para reconstrucción", e);
            }
        }
    }

    private record Tramo(int numeroOrden, String idVuelo, LocalDateTime horaSalidaUtc) { }
}
