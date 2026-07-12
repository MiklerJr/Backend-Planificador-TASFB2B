package com.tasfb2b.planificador.servicios.persistencia;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.dto.vuelos.CargaVuelo;
import com.tasfb2b.planificador.dto.vuelos.CargaVueloFila;
import com.tasfb2b.planificador.dto.vuelos.VueloCancelado;
import com.tasfb2b.planificador.dto.vuelos.VuelosUsadosResponse;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.TipoEnvio;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.FormatoSimulacion;
import com.tasfb2b.planificador.utilidades.FragmentadorEnvios;
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
import java.util.function.Consumer;

@Slf4j
@Service
public class LectorSolucionBd {

    private final JdbcTemplate jdbc;
    private final CargadorDatos cargadorDatos;
    private final TransactionTemplate txReadOnly;

    public LectorSolucionBd(JdbcTemplate jdbc, CargadorDatos cargadorDatos, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.cargadorDatos = cargadorDatos;
        if (txManager != null) {
            this.txReadOnly = new TransactionTemplate(txManager);
            this.txReadOnly.setReadOnly(true);
        } else {
            this.txReadOnly = null;
        }
    }

    public Map<String, Arista> construirIndiceVuelo(Grafo graph) {
        Map<String, Arista> idx = new HashMap<>();
        if (graph == null) return idx;
        for (Arista e : graph.aristas) {
            idx.put(PersistenciaSolucionService.normalizarIdVuelo(e.id), e);
        }
        return idx;
    }

    public void paraCadaEnrutado(Map<String, Arista> indiceVuelo, Consumer<LoteEnvio> consumer) {
        paraCadaEnrutado(indiceVuelo, null, null, consumer);
    }

    public void paraCadaEnrutado(Map<String, Arista> indiceVuelo, LocalDateTime desde, LocalDateTime hasta,
                                Consumer<LoteEnvio> consumer) {
        final String readyExpr = "(e.fecha_hora_registro - make_interval(hours => a.huso_horario))";
        StringBuilder sql = new StringBuilder()
                .append("SELECT r.id_ruta, r.id_envio, r.cumple_sla, r.sub_lote, r.total_sub_lotes, ")
                .append("       e.icao_origen, e.icao_destino, ")
                .append("       COALESCE(r.cantidad, e.cantidad_maletas) AS cantidad_maletas, e.fecha_hora_registro, ")
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

        forEachEnrutadoInyectado(indiceVuelo, desde, hasta, consumer);
    }

    private void forEachEnrutadoInyectado(Map<String, Arista> indiceVuelo, LocalDateTime desde,
                                          LocalDateTime hasta, Consumer<LoteEnvio> consumer) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT r.id_ruta_iny AS id_ruta, r.id_envio, r.cumple_sla, r.sub_lote, r.total_sub_lotes, ")
                .append("       i.icao_origen, i.icao_destino, ")
                .append("       COALESCE(r.cantidad, i.cantidad_maletas) AS cantidad_maletas, ")
                .append("       i.ready_time_utc AS fecha_hora_registro, ")
                .append("       t.numero_orden, t.id_vuelo, t.hora_salida_utc ")
                .append("FROM ruta_inyectada r ")
                .append("JOIN envio_inyectado i ON i.id_envio = r.id_envio ")
                .append("JOIN tramo_inyectado t ON t.id_ruta_iny = r.id_ruta_iny ")
                .append("WHERE r.activa ");
        if (desde != null) sql.append("AND i.ready_time_utc >= ? ");
        if (hasta != null) sql.append("AND i.ready_time_utc < ? ");
        sql.append("ORDER BY i.ready_time_utc, r.id_ruta_iny, t.numero_orden");

        final long[] idRutaActual = { Long.MIN_VALUE };
        final Acumulador acc = new Acumulador();
        org.springframework.jdbc.core.RowCallbackHandler rch = rs -> {
            long idRuta = rs.getLong("id_ruta");
            if (idRuta != idRutaActual[0]) {
                emitir(acc, indiceVuelo, consumer, true);   // readyTime ya UTC
                idRutaActual[0] = idRuta;
                acc.reset(rs);
            }
            acc.tramos.add(new Tramo(rs.getInt("numero_orden"), rs.getString("id_vuelo"),
                    rs.getTimestamp("hora_salida_utc").toLocalDateTime()));
        };
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
        emitir(acc, indiceVuelo, consumer, true);      // último batch pendiente
    }

    public List<LoteEnvio> afectadosPorVuelo(List<String> idsVueloNormalizado, LocalDate dia,
                                                Map<String, Arista> indiceVuelo) {
        List<LoteEnvio> out = new ArrayList<>();
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
                "SELECT r.id_ruta, r.id_envio, r.cumple_sla, r.sub_lote, r.total_sub_lotes, "
              + "       e.icao_origen, e.icao_destino, "
              + "       COALESCE(r.cantidad, e.cantidad_maletas) AS cantidad_maletas, e.fecha_hora_registro, "
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

    public List<VueloCancelado> leerCancelaciones(Map<String, Arista> indiceVuelo) {
        return leerCancelaciones(indiceVuelo, null, null);
    }

    public List<VueloCancelado> leerCancelaciones(Map<String, Arista> indiceVuelo,
                                                  LocalDateTime desde, LocalDateTime hasta) {
        StringBuilder sql = new StringBuilder(
                "SELECT id_vuelo, fecha_cancelacion, envios_afectados FROM cancelacion_vuelo WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (desde != null) { sql.append("AND fecha_cancelacion >= ? "); args.add(desde.toLocalDate()); }
        if (hasta != null) { sql.append("AND fecha_cancelacion < ? ");  args.add(fechaExclusivaHasta(hasta)); }
        sql.append("ORDER BY fecha_cancelacion, id_vuelo");
        return jdbc.query(sql.toString(), (rs, n) -> {
            String idVuelo = rs.getString("id_vuelo");
            LocalDate fecha = rs.getObject("fecha_cancelacion", LocalDate.class);
            int afectados = rs.getInt("envios_afectados");
            Arista e = indiceVuelo != null ? indiceVuelo.get(idVuelo) : null;
            String origen, destino;
            int depMin;
            if (e != null) {
                origen = e.origen.codigo;
                destino = e.destino.codigo;
                depMin = e.minutoDelDiaSalida;
            } else {
                // Fallback defensivo: derivar del propio id_vuelo (ICAO-ICAO-HHMM).
                String[] partes = idVuelo != null ? idVuelo.split("-") : new String[0];
                origen = partes.length > 0 ? partes[0] : "";
                destino = partes.length > 1 ? partes[1] : "";
                depMin = partes.length > 2 ? parseHHMM(partes[2]) : 0;
            }
            LocalDateTime salidaUtc = fecha.atStartOfDay().plusMinutes(depMin);
            return new VueloCancelado(origen, destino, salidaUtc, afectados);
        }, args.toArray());
    }

    private static LocalDate fechaExclusivaHasta(LocalDateTime hasta) {
        return hasta.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                ? hasta.toLocalDate()
                : hasta.toLocalDate().plusDays(1);
    }

    public long contarEnrutados(LocalDateTime desde, LocalDateTime hasta) {
        final String readyExpr = "(e.fecha_hora_registro - make_interval(hours => a.huso_horario))";
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM ruta_asignada r "
              + "JOIN envio e ON e.id_envio = r.id_envio "
              + "JOIN aeropuerto a ON a.icao = e.icao_origen "
              + "WHERE r.activa ");
        List<Object> args = new ArrayList<>();
        if (desde != null) { sql.append("AND ").append(readyExpr).append(" >= ? "); args.add(desde); }
        if (hasta != null) { sql.append("AND ").append(readyExpr).append(" < ? ");  args.add(hasta); }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());

        StringBuilder sqlIny = new StringBuilder(
                "SELECT COUNT(*) FROM ruta_inyectada r "
              + "JOIN envio_inyectado i ON i.id_envio = r.id_envio "
              + "WHERE r.activa ");
        List<Object> argsIny = new ArrayList<>();
        if (desde != null) { sqlIny.append("AND i.ready_time_utc >= ? "); argsIny.add(desde); }
        if (hasta != null) { sqlIny.append("AND i.ready_time_utc < ? ");  argsIny.add(hasta); }
        Long nIny = jdbc.queryForObject(sqlIny.toString(), Long.class, argsIny.toArray());

        return (n != null ? n : 0L) + (nIny != null ? nIny : 0L);
    }

    public long contarCancelaciones(LocalDateTime desde, LocalDateTime hasta) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM cancelacion_vuelo WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (desde != null) { sql.append("AND fecha_cancelacion >= ? "); args.add(desde.toLocalDate()); }
        if (hasta != null) { sql.append("AND fecha_cancelacion < ? ");  args.add(fechaExclusivaHasta(hasta)); }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n != null ? n : 0L;
    }

    private Map<String, Vuelo> indiceVueloPorIdBd() {
        Map<String, Vuelo> idx = new HashMap<>();
        for (Vuelo v : cargadorDatos.getVuelos()) {
            String front = FormatoSimulacion.vueloFrontId(v);
            if (front != null && !front.isEmpty()) {
                idx.put(PersistenciaSolucionService.normalizarIdVuelo(front), v);
            }
        }
        return idx;
    }

    public List<VuelosUsadosResponse.VueloUsado> reconstruirVuelosUsados() {
        Map<String, Vuelo> idx = indiceVueloPorIdBd();
        String sql = "SELECT t.id_vuelo, t.hora_salida_utc, t.hora_llegada_utc, "
                + "COUNT(DISTINCT r.id_ruta) AS envios, "
                + "COALESCE(SUM(COALESCE(r.cantidad, e.cantidad_maletas)), 0) AS maletas "
                + "FROM tramo_ruta t "
                + "JOIN ruta_asignada r ON r.id_ruta = t.id_ruta AND r.activa "
                + "JOIN envio e ON e.id_envio = r.id_envio "
                + "GROUP BY t.id_vuelo, t.hora_salida_utc, t.hora_llegada_utc "
                + "ORDER BY t.hora_salida_utc, t.id_vuelo";
        return jdbc.query(sql, (rs, rowNum) -> {
            String idBD = rs.getString("id_vuelo");
            Vuelo v = idx.get(idBD);
            String vueloFront = v != null ? FormatoSimulacion.vueloFrontId(v) : idBD;
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

    public List<CargaVueloFila> reconstruirCargasVuelos(int desde, int limit) {
        Map<String, Vuelo> idx = indiceVueloPorIdBd();
        final int offset = Math.max(0, desde);
        final int lim = Math.max(1, limit);
        // SUM(COALESCE(r.cantidad, e.cantidad_maletas)): la carga real de cada sub-lote (con
        // e.cantidad_maletas — la del padre — cada sub-lote doblaría las maletas del vuelo).
        String sql = "SELECT t.id_vuelo, t.hora_salida_utc, t.hora_llegada_utc, "
                + "COALESCE(SUM(COALESCE(r.cantidad, e.cantidad_maletas)), 0) AS carga "
                + "FROM tramo_ruta t "
                + "JOIN ruta_asignada r ON r.id_ruta = t.id_ruta AND r.activa "
                + "JOIN envio e ON e.id_envio = r.id_envio "
                + "GROUP BY t.id_vuelo, t.hora_salida_utc, t.hora_llegada_utc "
                + "ORDER BY t.hora_salida_utc, t.id_vuelo "
                + "LIMIT ? OFFSET ?";
        org.springframework.jdbc.core.RowMapper<CargaVueloFila> mapper = (rs, rowNum) -> {
            String idBD = rs.getString("id_vuelo");
            Vuelo v = idx.get(idBD);
            String vueloFront = v != null ? FormatoSimulacion.vueloFrontId(v) : idBD;
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
            FormatoSimulacion.completarCargaVuelo(c);   // porcentajeCarga + semáforo
            CargaVueloFila row = new CargaVueloFila();
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

    private static int parseHHMM(String hhmm) {
        try {
            int v = Integer.parseInt(hhmm.trim());
            return (v / 100) * 60 + (v % 100);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public List<LoteEnvio> buscarPorEnvio(String idEnvio, Map<String, Arista> indiceVuelo) {
        return buscarRutasActivas(idEnvio, indiceVuelo, false);
    }

    public List<LoteEnvio> buscarPorEnvioInyectado(String idEnvio, Map<String, Arista> indiceVuelo) {
        return buscarRutasActivas(idEnvio, indiceVuelo, true);
    }

    private List<LoteEnvio> buscarRutasActivas(String idEnvio, Map<String, Arista> indiceVuelo,
                                               boolean inyectado) {
        List<LoteEnvio> out = new ArrayList<>();
        if (idEnvio == null || idEnvio.isBlank()) return out;

        boolean esSub = FragmentadorEnvios.esIdSubLote(idEnvio);
        String idBd = esSub ? FragmentadorEnvios.idPadreDe(idEnvio) : idEnvio;
        Integer subFiltro = esSub ? FragmentadorEnvios.numeroFragmentoDe(idEnvio) : null;

        String sql = inyectado
                ? "SELECT r.id_ruta_iny AS id_ruta, r.id_envio, r.cumple_sla, r.sub_lote, r.total_sub_lotes, "
                  + "       i.icao_origen, i.icao_destino, "
                  + "       COALESCE(r.cantidad, i.cantidad_maletas) AS cantidad_maletas, "
                  + "       i.ready_time_utc AS fecha_hora_registro, "
                  + "       t.numero_orden, t.id_vuelo, t.hora_salida_utc "
                  + "FROM ruta_inyectada r "
                  + "JOIN envio_inyectado i ON i.id_envio = r.id_envio "
                  + "JOIN tramo_inyectado t ON t.id_ruta_iny = r.id_ruta_iny "
                  + "WHERE r.id_envio = ? AND r.activa "
                  + (subFiltro != null ? "AND r.sub_lote = ? " : "")
                  + "ORDER BY r.id_ruta_iny, t.numero_orden"
                : "SELECT r.id_ruta, r.id_envio, r.cumple_sla, r.sub_lote, r.total_sub_lotes, "
                  + "       e.icao_origen, e.icao_destino, "
                  + "       COALESCE(r.cantidad, e.cantidad_maletas) AS cantidad_maletas, e.fecha_hora_registro, "
                  + "       t.numero_orden, t.id_vuelo, t.hora_salida_utc "
                  + "FROM ruta_asignada r "
                  + "JOIN envio e ON e.id_envio = r.id_envio "
                  + "JOIN tramo_ruta t ON t.id_ruta = r.id_ruta "
                  + "WHERE r.id_envio = ? AND r.activa "
                  + (subFiltro != null ? "AND r.sub_lote = ? " : "")
                  + "ORDER BY r.id_ruta, t.numero_orden";

        final long[] idRutaActual = { Long.MIN_VALUE };
        final Acumulador acc = new Acumulador();
        jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            ps.setString(1, idBd);
            if (subFiltro != null) ps.setInt(2, subFiltro);
            return ps;
        }, rs -> {
            long idRuta = rs.getLong("id_ruta");
            if (idRuta != idRutaActual[0]) {
                emitir(acc, indiceVuelo, out::add, inyectado);   // cierra el sub-lote anterior
                idRutaActual[0] = idRuta;
                acc.reset(rs);
            }
            acc.tramos.add(new Tramo(rs.getInt("numero_orden"), rs.getString("id_vuelo"),
                    rs.getTimestamp("hora_salida_utc").toLocalDateTime()));
        });
        emitir(acc, indiceVuelo, out::add, inyectado);           // último sub-lote pendiente
        return out;
    }

    // ── Reconstrucción ──────────────────────────────────────────────────────

    private void emitir(Acumulador acc, Map<String, Arista> indiceVuelo, Consumer<LoteEnvio> consumer) {
        emitir(acc, indiceVuelo, consumer, false);
    }

    private void emitir(Acumulador acc, Map<String, Arista> indiceVuelo, Consumer<LoteEnvio> consumer,
                        boolean readyYaEsUtc) {
        if (acc.idEnvio == null || acc.tramos.isEmpty()) return;
        LoteEnvio b = reconstruir(acc, indiceVuelo, readyYaEsUtc);
        if (b != null) consumer.accept(b);
        acc.idEnvio = null;
        acc.tramos.clear();
    }

    private LoteEnvio reconstruir(Acumulador acc, Map<String, Arista> indiceVuelo) {
        return reconstruir(acc, indiceVuelo, false);
    }

    private LoteEnvio reconstruir(Acumulador acc, Map<String, Arista> indiceVuelo, boolean readyYaEsUtc) {
        Aeropuerto origen = cargadorDatos.getAeropuerto(acc.origen);
        Aeropuerto destino = cargadorDatos.getAeropuerto(acc.destino);
        if (origen == null || destino == null || origen.getOffsetHorario() == null) {
            log.warn("No se pudo reconstruir el envío {} (aeropuerto/offset ausente)", acc.idEnvio);
            return null;
        }
        LocalDateTime readyUtc = readyYaEsUtc
                ? acc.registroLocal
                : acc.registroLocal.minusHours(origen.getOffsetHorario());
        int sla = TipoEnvio.derivar(origen, destino) == TipoEnvio.INTRACONTINENTAL ? 24 : 48;

        String id = acc.subLote > 0 ? acc.idEnvio + FragmentadorEnvios.SUFIJO + acc.subLote : acc.idEnvio;
        LoteEnvio b = new LoteEnvio(id, acc.cantidad, sla,
                origen.getCodigo(), destino.getCodigo(), readyUtc);
        if (acc.subLote > 0) {
            b.setIdPadre(acc.idEnvio);
            b.setFragmento(acc.subLote);
            b.setTotalFragmentos(acc.totalSubLotes);
        }

        acc.tramos.sort((x, y) -> Integer.compare(x.numeroOrden, y.numeroOrden));
        List<Arista> ruta = new ArrayList<>(acc.tramos.size());
        List<Long> deps = new ArrayList<>(acc.tramos.size());
        for (Tramo t : acc.tramos) {
            Arista e = indiceVuelo.get(t.idVuelo);
            if (e == null) {
                log.warn("Tramo con id_vuelo {} sin Arista en el grafo (envío {}); ruta descartada",
                        t.idVuelo, acc.idEnvio);
                return null;
            }
            ruta.add(e);
            deps.add(ldtToEpochMin(t.horaSalidaUtc));
        }
        b.setRutaAsignada(ruta);
        b.setSalidasAsignadas(deps);
        b.setCumpleSLA(acc.cumpleSla);
        return b;
    }

    static long ldtToEpochMin(LocalDateTime dt) {
        return dt.toLocalDate().toEpochDay() * 1440L + dt.getHour() * 60L + dt.getMinute();
    }

    private static final class Acumulador {
        String idEnvio;
        String origen;
        String destino;
        int cantidad;
        int subLote;
        int totalSubLotes;
        LocalDateTime registroLocal;
        boolean cumpleSla;
        final List<Tramo> tramos = new ArrayList<>();

        void reset(ResultSet rs) {
            try {
                idEnvio = rs.getString("id_envio");
                origen = rs.getString("icao_origen");
                destino = rs.getString("icao_destino");
                cantidad = rs.getInt("cantidad_maletas");
                subLote = rs.getInt("sub_lote");
                totalSubLotes = rs.getInt("total_sub_lotes");
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
