package com.tasfb2b.planificador.utilidades;

import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Envio;
import com.tasfb2b.planificador.modelo.datos.TipoEnvio;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.utilidades.analizador.AnalizadorVuelos;
import com.tasfb2b.planificador.utilidades.validador.ValidadorVuelo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CargadorDatos {

    private final JdbcTemplate jdbcTemplate;

    // Estructuras concurrentes: las altas EN CALIENTE (AltasEnCalienteService, hilo worker) hacen
    // append/remove mientras los endpoints HTTP las leen. Escrituras rarísimas ⇒ copy-on-write.
    private List<Aeropuerto> aeropuertos = new CopyOnWriteArrayList<>();
    private List<Vuelo> vuelos = new CopyOnWriteArrayList<>();

    private Map<String, Aeropuerto> aeropuertoMapCache = new ConcurrentHashMap<>();

    private int maxOffsetAbsHoras = 0;
    private LocalDateTime primeraVentanaUtc;
    private LocalDateTime ultimaVentanaUtc;

    public CargadorDatos(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void load() {
        log.info("=================================================");
        log.info("INICIANDO DESCARGA DESDE LA NUBE (100% POSTGRESQL)");

        String sqlAeropuertos = "SELECT icao, ciudad, huso_horario, capacidad_almacen, capacidad_almacen_original, latitud, longitud FROM AEROPUERTO ORDER BY icao";
        aeropuertos = new CopyOnWriteArrayList<>(jdbcTemplate.query(sqlAeropuertos, (rs, rowNum) -> {
            Aeropuerto a = new Aeropuerto();
            a.setCodigo(rs.getString("icao")); // Usa setIcao() si así se llama en tu modelo
            String codigo = a.getCodigo();
            a.setCiudad(rs.getString("ciudad"));
            a.setOffsetHorario(rs.getInt("huso_horario"));
            a.setCapacidad(rs.getInt("capacidad_almacen"));
            
            int capOriginal = rs.getInt("capacidad_almacen_original");
            if (rs.wasNull()) capOriginal = a.getCapacidad(); // fallback
            a.setCapacidadOriginal(capOriginal);
            
            a.setLatitud(rs.getDouble("latitud"));
            a.setLongitud(rs.getDouble("longitud"));
            a.setContinente(continentePorIcao(codigo));
            a.setActivo(true);
            return a;
        }));

        aeropuertoMapCache = new ConcurrentHashMap<>(aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigo, a -> a)));

        String sqlVuelos = "SELECT id_vuelo, icao_origen, icao_destino, hora_salida, hora_llegada, capacidad_maxima, capacidad_maxima_original FROM VUELO ORDER BY id_vuelo";
        List<Vuelo> vuelosCargados = jdbcTemplate.query(sqlVuelos, (rs, rowNum) -> {
            Vuelo v = new Vuelo();

            String origenCodigo = rs.getString("icao_origen");
            String destinoCodigo = rs.getString("icao_destino");
            Aeropuerto origen = aeropuertoMapCache.get(origenCodigo);
            Aeropuerto destino = aeropuertoMapCache.get(destinoCodigo);

            if (origen == null || destino == null) {
                log.warn("Vuelo {} omitido: aeropuerto no encontrado ({} -> {})",
                        rs.getString("id_vuelo"), origenCodigo, destinoCodigo);
                return null;
            }

            LocalTime horaSalida = leerHora(rs.getObject("hora_salida"), "hora_salida");
            LocalTime horaLlegada = leerHora(rs.getObject("hora_llegada"), "hora_llegada");
            LocalDateTime fechaSalida = LocalDateTime.of(AnalizadorVuelos.FLIGHT_BASE_DATE, horaSalida);
            LocalDateTime fechaLlegada = fechaLlegadaLocal(fechaSalida, horaLlegada, origen, destino);

            v.setIdVuelo(rs.getString("id_vuelo"));
            v.setCapacidad(rs.getInt("capacidad_maxima"));

            int capOriginal = rs.getInt("capacidad_maxima_original");
            if (rs.wasNull()) capOriginal = v.getCapacidad(); // fallback
            v.setCapacidadOriginal(capOriginal);

            v.setOrigen(origenCodigo);
            v.setDestino(destinoCodigo);
            v.setFechaHoraSalida(fechaSalida);
            v.setFechaHoraLlegada(fechaLlegada);
            v.setAeropuertoOrigen(origen);
            v.setAeropuertoDestino(destino);
            return v;
        });
        List<Vuelo> noNulos = vuelosCargados.stream()
                .filter(v -> v != null)
                .collect(Collectors.toList());
        vuelos = noNulos.stream()
                .filter(ValidadorVuelo::esCoherente)
                .collect(Collectors.toCollection(CopyOnWriteArrayList::new));
        int descartadosIncoherentes = noNulos.size() - vuelos.size();
        if (descartadosIncoherentes > 0) {
            log.warn("ValidadorVuelo: {} vuelos descartados (capacidad<=0 u origen=destino)",
                    descartadosIncoherentes);
        }

        maxOffsetAbsHoras = aeropuertos.stream()
                .map(Aeropuerto::getOffsetHorario)
                .filter(Objects::nonNull)
                .mapToInt(Math::abs)
                .max()
                .orElse(0);
        calcularRangoUtcDatos();

        log.info("Aeropuertos en RAM : {}", aeropuertos.size());
        log.info("Vuelos en RAM      : {}", vuelos.size());

        try {
            Long totalEnvios = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ENVIO", Long.class);
            Long totalMaletas = jdbcTemplate.queryForObject("SELECT SUM(cantidad_maletas) FROM ENVIO", Long.class);
            LocalDateTime primera = getPrimeraVentana();
            LocalDateTime ultima = getUltimaVentana();

            log.info("Envios en BD: {}", totalEnvios != null ? totalEnvios : 0);
            log.info("Maletas fisicas en BD: {}", totalMaletas != null ? totalMaletas : 0);
            if (primera != null && ultima != null) {
                log.info("Rango UTC dataset : {} → {} (offset máx ±{}h)", primera, ultima, maxOffsetAbsHoras);
            }
        } catch (Exception e) {
            log.warn("No se pudo obtener el resumen de envíos. {}", e.getMessage());
        }
        log.info("=================================================");
    }

    public LocalDateTime getPrimeraVentana() {
        return primeraVentanaUtc;
    }

    public LocalDateTime getUltimaVentana() {
        return ultimaVentanaUtc;
    }

    private void calcularRangoUtcDatos() {
        String sql = "SELECT icao_origen, MIN(fecha_hora_registro) AS min_l, MAX(fecha_hora_registro) AS max_l " +
                     "FROM ENVIO GROUP BY icao_origen";
        LocalDateTime min = null;
        LocalDateTime max = null;
        try {
            List<Object[]> filas = jdbcTemplate.query(sql, (rs, rowNum) -> new Object[]{
                    rs.getString("icao_origen"),
                    rs.getTimestamp("min_l"),
                    rs.getTimestamp("max_l")
            });
            for (Object[] fila : filas) {
                Aeropuerto a = aeropuertoMapCache.get((String) fila[0]);
                int off = (a != null && a.getOffsetHorario() != null) ? a.getOffsetHorario() : 0;
                Timestamp minTs = (Timestamp) fila[1];
                Timestamp maxTs = (Timestamp) fila[2];
                if (minTs != null) {
                    LocalDateTime minUtc = minTs.toLocalDateTime().minusHours(off);
                    if (min == null || minUtc.isBefore(min)) min = minUtc;
                }
                if (maxTs != null) {
                    LocalDateTime maxUtc = maxTs.toLocalDateTime().minusHours(off);
                    if (max == null || maxUtc.isAfter(max)) max = maxUtc;
                }
            }
        } catch (Exception e) {
            log.warn("No se pudo calcular el rango UTC del dataset. {}", e.getMessage());
        }
        primeraVentanaUtc = min;
        ultimaVentanaUtc = max;
    }

    public List<Envio> getMaletasEnRango(LocalDateTime desdeUtc, LocalDateTime hastaUtc) {
        if (desdeUtc == null || hastaUtc == null || !desdeUtc.isBefore(hastaUtc)) {
            return Collections.emptyList();
        }

        LocalDateTime desdeLocal = desdeUtc.minusHours(maxOffsetAbsHoras);
        LocalDateTime hastaLocal = hastaUtc.plusHours(maxOffsetAbsHoras);

        String sql = "SELECT id_envio, icao_origen, icao_destino, cantidad_maletas, fecha_hora_registro " +
                     "FROM ENVIO " +
                     "WHERE fecha_hora_registro >= ? AND fecha_hora_registro < ? " +
                     "AND icao_origen <> icao_destino " +
                     "ORDER BY fecha_hora_registro ASC";

        List<Envio> maletas = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Envio m = new Envio();
            String dbId = rs.getString("id_envio");
            String idOriginal = dbId.contains("-") ? dbId.substring(dbId.indexOf('-') + 1) : dbId;
            m.setId(Integer.parseInt(idOriginal));
            m.setIdEnvio(dbId);
            Aeropuerto origen = aeropuertoMapCache.get(rs.getString("icao_origen"));
            m.setAeropuertoOrigen(origen);
            m.setAeropuertoDestino(aeropuertoMapCache.get(rs.getString("icao_destino")));

            m.setCantidad(rs.getInt("cantidad_maletas"));
            m.setTipoEnvio(TipoEnvio.derivar(m.getAeropuertoOrigen(), m.getAeropuertoDestino()));
            m.setPlazo(m.getTipoEnvio() == TipoEnvio.INTRACONTINENTAL ? 24 : 48);

            m.setFechaHoraRegistro(rs.getTimestamp("fecha_hora_registro").toLocalDateTime());

            int off = (origen != null && origen.getOffsetHorario() != null) ? origen.getOffsetHorario() : 0;
            if (!registroEnVentanaUtc(m.getFechaHoraRegistro(), off, desdeUtc, hastaUtc)) {
                return null;
            }
            return m;
        }, Timestamp.valueOf(desdeLocal), Timestamp.valueOf(hastaLocal));

        maletas.removeIf(Objects::isNull);
        return maletas;
    }

    public List<DemandaAgrupada> agregarDemandaEnRango(LocalDateTime desdeUtc, LocalDateTime hastaUtc) {
        if (desdeUtc == null || hastaUtc == null || !desdeUtc.isBefore(hastaUtc)) {
            return Collections.emptyList();
        }
        LocalDateTime desdeLocal = desdeUtc.minusHours(maxOffsetAbsHoras);
        LocalDateTime hastaLocal = hastaUtc.plusHours(maxOffsetAbsHoras);
        final String readyExpr = "(e.fecha_hora_registro - make_interval(hours => a.huso_horario))";

        String sql = "SELECT e.icao_origen AS origen, e.icao_destino AS destino, " +
                     "COUNT(*) AS envios, COALESCE(SUM(e.cantidad_maletas), 0) AS maletas " +
                     "FROM ENVIO e " +
                     "JOIN AEROPUERTO a ON a.icao = e.icao_origen " +
                     "WHERE e.fecha_hora_registro >= ? AND e.fecha_hora_registro < ? " +
                     "AND e.icao_origen <> e.icao_destino " +
                     "AND " + readyExpr + " >= ? AND " + readyExpr + " < ? " +
                     "GROUP BY e.icao_origen, e.icao_destino";

        return jdbcTemplate.query(sql, (rs, rowNum) -> new DemandaAgrupada(
                        rs.getString("origen"), rs.getString("destino"),
                        rs.getLong("envios"), rs.getLong("maletas")),
                Timestamp.valueOf(desdeLocal), Timestamp.valueOf(hastaLocal),
                Timestamp.valueOf(desdeUtc), Timestamp.valueOf(hastaUtc));
    }

    public record DemandaAgrupada(String origen, String destino, long envios, long maletas) { }

    static boolean registroEnVentanaUtc(LocalDateTime registroLocal, int offsetHoras,
                                        LocalDateTime desdeUtc, LocalDateTime hastaUtc) {
        LocalDateTime registroUtc = registroLocal.minusHours(offsetHoras);
        return !registroUtc.isBefore(desdeUtc) && registroUtc.isBefore(hastaUtc);
    }

    public List<Envio> getMaletasMuestra(int limite) {
        if (limite <= 0) return Collections.emptyList();
        String sql = "SELECT id_envio, icao_origen, icao_destino, cantidad_maletas, fecha_hora_registro " +
                     "FROM ENVIO WHERE icao_origen <> icao_destino " +
                     "ORDER BY fecha_hora_registro ASC LIMIT ?";
                     
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Envio m = new Envio();
            String dbId = rs.getString("id_envio");
            String idOriginal = dbId.contains("-") ? dbId.substring(dbId.indexOf('-') + 1) : dbId;
            m.setId(Integer.parseInt(idOriginal));
            m.setIdEnvio(dbId);
            m.setAeropuertoOrigen(aeropuertoMapCache.get(rs.getString("icao_origen")));
            m.setAeropuertoDestino(aeropuertoMapCache.get(rs.getString("icao_destino")));

            m.setCantidad(rs.getInt("cantidad_maletas"));
            m.setTipoEnvio(TipoEnvio.derivar(m.getAeropuertoOrigen(), m.getAeropuertoDestino()));
            m.setPlazo(m.getTipoEnvio() == TipoEnvio.INTRACONTINENTAL ? 24 : 48);

            m.setFechaHoraRegistro(rs.getTimestamp("fecha_hora_registro").toLocalDateTime());
            return m;
        }, limite);
    }

    public int getTotalMaletas() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ENVIO", Long.class);
        return count != null ? count.intValue() : 0;
    }

    public int getTotalEnvios() {
        return getTotalMaletas(); 
    }

    public long getTotalMaletasIndividuales() {
        Long sum = jdbcTemplate.queryForObject("SELECT SUM(cantidad_maletas) FROM ENVIO", Long.class);
        return sum != null ? sum : 0L;
    }

    public List<Aeropuerto> getAeropuertos() { return aeropuertos; }
    public List<Vuelo>      getVuelos()      { return vuelos; }

    public Aeropuerto getAeropuerto(String icao) {
        return icao == null ? null : aeropuertoMapCache.get(icao);
    }

    // ── Altas EN CALIENTE (efímeras por corrida; ver AltasEnCalienteService) ────────────
    // Append-only al final de la lista: preserva el mapeo posicional 1:1 vuelo↔arista del grafo.

    public void agregarVueloEfimero(Vuelo v) {
        if (v != null) vuelos.add(v);
    }

    public void quitarVuelosEfimeros() {
        vuelos.removeIf(Vuelo::isEfimero);
    }

    public void agregarAeropuertoEfimero(Aeropuerto a) {
        if (a == null || a.getCodigo() == null) return;
        aeropuertos.add(a);
        aeropuertoMapCache.put(a.getCodigo(), a);
    }

    /** Compensación puntual de un alta fallida: quita exactamente ese aeropuerto (lista + mapa). */
    public void quitarAeropuertoEfimero(Aeropuerto a) {
        if (a == null || !a.isEfimero()) return;
        aeropuertos.remove(a);
        aeropuertoMapCache.remove(a.getCodigo(), a);
    }

    public void quitarAeropuertosEfimeros() {
        for (Aeropuerto a : aeropuertos) {
            if (a.isEfimero()) aeropuertoMapCache.remove(a.getCodigo());
        }
        aeropuertos.removeIf(Aeropuerto::isEfimero);
    }

    public static LocalDateTime fechaLlegadaLocal(LocalDateTime fechaSalida,
                                                   LocalTime horaLlegada,
                                                   Aeropuerto origen,
                                                   Aeropuerto destino) {
        int origenOffset = origen.getOffsetHorario() != null ? origen.getOffsetHorario() : 0;
        int destinoOffset = destino.getOffsetHorario() != null ? destino.getOffsetHorario() : 0;
        int depWall = aMinutos(fechaSalida.toLocalTime());
        int arrWall = aMinutos(horaLlegada);
        int durReal = Math.floorMod((arrWall - destinoOffset * 60) - (depWall - origenOffset * 60), 1440);
        return fechaSalida.plusMinutes(durReal + (long) (destinoOffset - origenOffset) * 60);
    }

    private static LocalTime leerHora(Object raw, String columna) {
        if (raw == null) {
            throw new IllegalStateException("Valor nulo en columna " + columna + " de VUELO");
        }
        if (raw instanceof LocalTime localTime) {
            return localTime;
        }
        if (raw instanceof Time time) {
            return time.toLocalTime();
        }
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalTime();
        }

        String text = raw.toString().trim();
        String[] parts = text.split(":");
        if (parts.length < 2) {
            throw new IllegalStateException("Hora invalida en columna " + columna + ": " + text);
        }
        return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private static int aMinutos(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    public static String continentePorIcao(String code) {
        if (code == null || code.isBlank()) return "UNKNOWN";
        return switch (code.charAt(0)) {
            case 'S' -> "AM";
            case 'E', 'L', 'U' -> "EU";
            case 'O', 'V' -> "AS";
            default -> "UNKNOWN";
        };
    }
}
