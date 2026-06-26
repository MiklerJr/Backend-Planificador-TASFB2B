package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.dto.CargaVuelo;
import com.tasfb2b.planificador.dto.CargaVueloRow;
import com.tasfb2b.planificador.dto.VueloCancelado;
import com.tasfb2b.planificador.dto.VuelosUsadosResponse;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.TipoEnvio;
import com.tasfb2b.planificador.model.Vuelo;
import com.tasfb2b.planificador.util.DataLoader;
import com.tasfb2b.planificador.util.SimulacionFormat;
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
        forEachEnrutado(indiceVuelo, null, null, consumer);
    }

    /**
     * Variante con filtro por rango de {@code readyTime} UTC (auditoría on-demand por fecha). Los
     * límites {@code desde} (inclusivo) y {@code hasta} (exclusivo) se aplican en BD sobre la misma
     * expresión de {@code readyTime} ({@code registro − offset(origen)}) que el orden, así el ZIP por
     * rango solo lee de BD los envíos del período pedido. Cualquiera de los dos puede ser {@code null}
     * (sin cota por ese lado); ambos {@code null} = histórico completo.
     */
    public void forEachEnrutado(Map<String, Edge> indiceVuelo, LocalDateTime desde, LocalDateTime hasta,
                                Consumer<LuggageBatch> consumer) {
        // readyTime UTC = registro − offset(origen). Agrupa los tramos de cada ruta contiguos
        // (mismo id_ruta ⇒ mismo readyTime) para poder emitir un batch a la vez.
        final String readyExpr = "(e.fecha_hora_registro - make_interval(hours => a.huso_horario))";
        StringBuilder sql = new StringBuilder()
                .append("SELECT r.id_ruta, r.id_envio, r.cumple_sla, ")
                .append("       e.icao_origen, e.icao_destino, e.cantidad_maletas, e.fecha_hora_registro, ")
                .append("       t.numero_orden, t.id_vuelo, t.hora_salida_utc ")
                .append("FROM ruta_asignada r ")
                .append("JOIN envio e ON e.id_envio = r.id_envio ")
                .append("JOIN aeropuerto a ON a.icao = e.icao_origen ")
                .append("JOIN tramo_ruta t ON t.id_ruta = r.id_ruta ")
                .append("WHERE r.activa ");
        if (desde != null) sql.append("AND ").append(readyExpr).append(" >= ? ");
        if (hasta != null) sql.append("AND ").append(readyExpr).append(" < ? ");
        sql.append("ORDER BY ").append(readyExpr).append(", r.id_ruta, t.numero_orden");

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
            var ps = con.prepareStatement(sql.toString());
            int idx = 1;
            if (desde != null) ps.setObject(idx++, desde);
            if (hasta != null) ps.setObject(idx++, hasta);
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

    /**
     * Fase 3 (anti-OOM): índice {@code id_vuelo} (BD, {@code ICAO-ICAO-HHMM}) → {@link Vuelo} del dataset.
     * La clave se deriva con {@code normalizarIdVuelo(vueloFrontId(v))} (NO de {@code Vuelo.getIdVuelo()},
     * que {@code DataLoader} deja null en prod); así casa con {@code tramo_ruta.id_vuelo}. Da el {@code Vuelo}
     * para reconstruir el vueloId del front ({@code vueloFrontId} = {@code Edge.id}) y la capacidad.
     */
    private Map<String, Vuelo> indiceVueloPorIdBd() {
        Map<String, Vuelo> idx = new HashMap<>();
        for (Vuelo v : dataLoader.getVuelos()) {
            String front = SimulacionFormat.vueloFrontId(v);
            if (front != null && !front.isEmpty()) {
                idx.put(PersistenciaSolucionService.normalizarIdVuelo(front), v);
            }
        }
        return idx;
    }

    /**
     * Fase 3 (anti-OOM): reconstruye el histórico COMPLETO de {@code /vuelos/usados} desde las rutas
     * ACTIVAS en BD, para servirlo cuando el acumulador en RAM ya purgó los bloques fuera de la ventana
     * reciente. El {@code vueloId} se devuelve en formato front ({@code ICAO-ICAO-HH:MM}, vía
     * {@link SimulacionFormat#vueloFrontId} = {@code Edge.id}) para que el front lo case igual que en vivo.
     *
     * <p><b>bloqueIdx:</b> es un índice de ORDEN temporal (por {@code hora_salida_utc}), NO el bloque de
     * cálculo: la BD no guarda el bloque. {@code envioIds} va vacío en el histórico (peso). Los inyectados
     * ({@code INV-…}) no están en {@code ruta_asignada} (sin FK) ⇒ no aparecen, igual que su ruta no se persiste.
     */
    public List<VuelosUsadosResponse.VueloUsado> reconstruirVuelosUsados() {
        Map<String, Vuelo> idx = indiceVueloPorIdBd();
        String sql = "SELECT t.id_vuelo, t.hora_salida_utc, t.hora_llegada_utc, "
                + "COUNT(DISTINCT r.id_envio) AS envios, COALESCE(SUM(e.cantidad_maletas), 0) AS maletas "
                + "FROM tramo_ruta t "
                + "JOIN ruta_asignada r ON r.id_ruta = t.id_ruta AND r.activa "
                + "JOIN envio e ON e.id_envio = r.id_envio "
                + "GROUP BY t.id_vuelo, t.hora_salida_utc, t.hora_llegada_utc "
                + "ORDER BY t.hora_salida_utc, t.id_vuelo";
        return jdbc.query(sql, (rs, rowNum) -> {
            String idBD = rs.getString("id_vuelo");
            Vuelo v = idx.get(idBD);
            String vueloFront = v != null ? SimulacionFormat.vueloFrontId(v) : idBD;
            String[] partes = vueloFront.split("-");   // ICAO-ICAO-HH:MM
            String salida = rs.getTimestamp("hora_salida_utc").toLocalDateTime().toString();
            VuelosUsadosResponse.VueloUsado u = new VuelosUsadosResponse.VueloUsado();
            u.setVueloId(vueloFront);
            u.setFlightKey(vueloFront + "|" + salida);
            u.setBloqueIdx(rowNum);   // orden temporal, no el bloque de cálculo (la BD no lo guarda)
            u.setOrigen(partes.length > 0 ? partes[0] : "");
            u.setDestino(partes.length > 1 ? partes[1] : "");
            u.setFechaSalida(salida);
            u.setFechaLlegada(rs.getTimestamp("hora_llegada_utc").toLocalDateTime().toString());
            u.setCantidadMaletas(rs.getInt("maletas"));
            u.setCantidadEnvios(rs.getInt("envios"));
            u.setEnvioIds(List.of());
            return u;
        });
    }

    /**
     * Fase 3 (anti-OOM): reconstruye el histórico de {@code /vuelos/carga} desde las rutas ACTIVAS en
     * BD, para servirlo cuando el buffer deslizante ya soltó los bloques viejos. Misma agregación por
     * vuelo-día que {@link #reconstruirVuelosUsados}; {@code cargaAsignada = SUM(cantidad)},
     * capacidad/%/semáforo del {@link Vuelo} del dataset. {@code bloqueIdx} = orden temporal global
     * ({@code desde + rowNum}); {@code horaInicio/horaFin} quedan null (no hay bloque en BD).
     *
     * <p>Anti-OOM: PAGINADO ({@code OFFSET desde LIMIT limit}) y leído con cursor server-side
     * ({@code fetchSize} en una tx read-only ⇒ {@code autoCommit=false}, igual que
     * {@link #forEachEnrutado}), para no materializar TODO el histórico de golpe. El llamador pide
     * {@code limit+1} para detectar si quedan más páginas.
     */
    public List<CargaVueloRow> reconstruirCargasVuelos(int desde, int limit) {
        Map<String, Vuelo> idx = indiceVueloPorIdBd();
        final int offset = Math.max(0, desde);
        final int lim = Math.max(1, limit);
        String sql = "SELECT t.id_vuelo, t.hora_salida_utc, t.hora_llegada_utc, "
                + "COALESCE(SUM(e.cantidad_maletas), 0) AS carga "
                + "FROM tramo_ruta t "
                + "JOIN ruta_asignada r ON r.id_ruta = t.id_ruta AND r.activa "
                + "JOIN envio e ON e.id_envio = r.id_envio "
                + "GROUP BY t.id_vuelo, t.hora_salida_utc, t.hora_llegada_utc "
                + "ORDER BY t.hora_salida_utc, t.id_vuelo "
                + "LIMIT ? OFFSET ?";
        org.springframework.jdbc.core.RowMapper<CargaVueloRow> mapper = (rs, rowNum) -> {
            String idBD = rs.getString("id_vuelo");
            Vuelo v = idx.get(idBD);
            String vueloFront = v != null ? SimulacionFormat.vueloFrontId(v) : idBD;
            String[] partes = vueloFront.split("-");
            String salida = rs.getTimestamp("hora_salida_utc").toLocalDateTime().toString();
            CargaVuelo c = new CargaVuelo();
            c.setVueloId(vueloFront);
            c.setOrigen(partes.length > 0 ? partes[0] : "");
            c.setDestino(partes.length > 1 ? partes[1] : "");
            c.setFechaSalida(salida);
            c.setFechaLlegada(rs.getTimestamp("hora_llegada_utc").toLocalDateTime().toString());
            c.setCapacidadMaxima(v != null && v.getCapacidad() != null ? v.getCapacidad() : 0);
            c.setCargaAsignada(rs.getInt("carga"));
            SimulacionFormat.completarCargaVuelo(c);   // porcentajeCarga + semáforo
            CargaVueloRow row = new CargaVueloRow();
            row.setVueloId(c.getVueloId());
            row.setOrigen(c.getOrigen());
            row.setDestino(c.getDestino());
            row.setFechaSalida(c.getFechaSalida());
            row.setFechaLlegada(c.getFechaLlegada());
            row.setCapacidadMaxima(c.getCapacidadMaxima());
            row.setCargaAsignada(c.getCargaAsignada());
            row.setPorcentajeCarga(c.getPorcentajeCarga());
            row.setSemaforo(c.getSemaforo());
            row.setBloqueIdx(offset + rowNum);   // orden temporal global, no el bloque de cálculo
            return row;
        };
        org.springframework.jdbc.core.PreparedStatementCreator psc = con -> {
            var ps = con.prepareStatement(sql);
            ps.setInt(1, lim);
            ps.setInt(2, offset);
            ps.setFetchSize(2000);
            return ps;
        };
        if (txReadOnly != null) {
            return txReadOnly.execute(st -> jdbc.query(psc, mapper));
        }
        return jdbc.query(psc, mapper);
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
