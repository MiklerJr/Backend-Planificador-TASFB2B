package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.TipoEnvio;
import com.tasfb2b.planificador.model.Vuelo;
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
import java.util.stream.Collectors;

@Slf4j
@Component
public class DataLoader {

    private final JdbcTemplate jdbcTemplate;

    private List<Aeropuerto> aeropuertos = new ArrayList<>();
    private List<Vuelo> vuelos = new ArrayList<>();
    
    // Caché para mapear rápido los códigos ICAO a objetos Aeropuerto desde la BD
    private Map<String, Aeropuerto> aeropuertoMapCache; 

    // Limpiamos los Parsers y las variables @Value, solo inyectamos la BD
    public DataLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void load() {
        log.info("=================================================");
        log.info("INICIANDO DESCARGA DESDE LA NUBE (100% POSTGRESQL)");
        
        // 1. Cargamos Aeropuertos desde AWS
        String sqlAeropuertos = "SELECT icao, ciudad, huso_horario, capacidad_almacen, latitud, longitud FROM AEROPUERTO";
        aeropuertos = jdbcTemplate.query(sqlAeropuertos, (rs, rowNum) -> {
            Aeropuerto a = new Aeropuerto();
            a.setCodigo(rs.getString("icao")); // Usa setIcao() si así se llama en tu modelo
            String codigo = a.getCodigo();
            a.setCiudad(rs.getString("ciudad"));
            a.setOffsetHorario(rs.getInt("huso_horario"));
            a.setCapacidad(rs.getInt("capacidad_almacen"));
            a.setLatitud(rs.getDouble("latitud"));
            a.setLongitud(rs.getDouble("longitud"));
            a.setContinente(continentePorIcao(codigo));
            a.setActivo(true);
            return a;
        });

        // Llenamos la caché
        aeropuertoMapCache = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigo, a -> a));

        // 2. Cargamos Vuelos desde AWS
        String sqlVuelos = "SELECT id_vuelo, icao_origen, icao_destino, hora_salida, hora_llegada, capacidad_maxima FROM VUELO";
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
            LocalDateTime fechaSalida = LocalDateTime.of(FlightParser.FLIGHT_BASE_DATE, horaSalida);
            LocalDateTime fechaLlegada = fechaLlegadaLocal(fechaSalida, horaLlegada, origen, destino);

            v.setCapacidad(rs.getInt("capacidad_maxima"));
            v.setOrigen(origenCodigo);
            v.setDestino(destinoCodigo);
            v.setFechaHoraSalida(fechaSalida);
            v.setFechaHoraLlegada(fechaLlegada);
            v.setAeropuertoOrigen(origen);
            v.setAeropuertoDestino(destino);
            return v;
        });
        // Descartar nulos (aeropuerto inexistente) y luego los vuelos incoherentes
        // (capacidad <= 0 u origen = destino), igual que se omiten los sin aeropuerto.
        List<Vuelo> noNulos = vuelosCargados.stream()
                .filter(v -> v != null)
                .collect(Collectors.toList());
        vuelos = noNulos.stream()
                .filter(VueloValidator::esCoherente)
                .collect(Collectors.toList());
        int descartadosIncoherentes = noNulos.size() - vuelos.size();
        if (descartadosIncoherentes > 0) {
            log.warn("VueloValidator: {} vuelos descartados (capacidad<=0 u origen=destino)",
                    descartadosIncoherentes);
        }

        // 3. Resumen consultado directamente a PostgreSQL
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
                log.info("Rango en BD : {} → {}", primera, ultima);
            }
        } catch (Exception e) {
            log.warn("No se pudo obtener el resumen de envíos. {}", e.getMessage());
        }
        log.info("=================================================");
    }

    public LocalDateTime getPrimeraVentana() {
        Timestamp ts = jdbcTemplate.queryForObject("SELECT MIN(fecha_hora_registro) FROM ENVIO", Timestamp.class);
        return ts != null ? ts.toLocalDateTime() : null;
    }

    public LocalDateTime getUltimaVentana() {
        Timestamp ts = jdbcTemplate.queryForObject("SELECT MAX(fecha_hora_registro) FROM ENVIO", Timestamp.class);
        return ts != null ? ts.toLocalDateTime() : null;
    }

    public List<Maleta> getMaletasEnRango(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !desde.isBefore(hasta)) {
            return Collections.emptyList();
        }

        // RF02: se excluyen los envíos cuyo aeropuerto de origen y destino son el mismo.
        String sql = "SELECT id_envio, icao_origen, icao_destino, cantidad_maletas, fecha_hora_registro " +
                     "FROM ENVIO " +
                     "WHERE fecha_hora_registro >= ? AND fecha_hora_registro < ? " +
                     "AND icao_origen <> icao_destino " +
                     "ORDER BY fecha_hora_registro ASC";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Maleta m = new Maleta();
            String dbId = rs.getString("id_envio");
            String idOriginal = dbId.contains("-") ? dbId.substring(dbId.indexOf('-') + 1) : dbId;
            m.setId(Integer.parseInt(idOriginal));
            m.setAeropuertoOrigen(aeropuertoMapCache.get(rs.getString("icao_origen")));
            m.setAeropuertoDestino(aeropuertoMapCache.get(rs.getString("icao_destino")));

            // Extraemos como Int en lugar de Long
            m.setCantidad(rs.getInt("cantidad_maletas"));
            // El plazo (SLA en horas) deriva del tipo de envío: 24h intracontinental, 48h intercontinental.
            m.setTipoEnvio(TipoEnvio.derivar(m.getAeropuertoOrigen(), m.getAeropuertoDestino()));
            m.setPlazo(m.getTipoEnvio() == TipoEnvio.INTRACONTINENTAL ? 24 : 48);

            m.setFechaHoraRegistro(rs.getTimestamp("fecha_hora_registro").toLocalDateTime());

            return m;
        }, Timestamp.valueOf(desde), Timestamp.valueOf(hasta));
    }

    public List<Maleta> getMaletasMuestra(int limite) {
        if (limite <= 0) return Collections.emptyList();
        // RF02: se excluyen los envíos cuyo aeropuerto de origen y destino son el mismo.
        String sql = "SELECT id_envio, icao_origen, icao_destino, cantidad_maletas, fecha_hora_registro " +
                     "FROM ENVIO WHERE icao_origen <> icao_destino " +
                     "ORDER BY fecha_hora_registro ASC LIMIT ?";
                     
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Maleta m = new Maleta();
            String dbId = rs.getString("id_envio");
            String idOriginal = dbId.contains("-") ? dbId.substring(dbId.indexOf('-') + 1) : dbId;
            m.setId(Integer.parseInt(idOriginal));
            m.setAeropuertoOrigen(aeropuertoMapCache.get(rs.getString("icao_origen")));
            m.setAeropuertoDestino(aeropuertoMapCache.get(rs.getString("icao_destino")));

            // Mismo cambio aquí
            m.setCantidad(rs.getInt("cantidad_maletas"));
            // El plazo (SLA en horas) deriva del tipo de envío: 24h intracontinental, 48h intercontinental.
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

    private static LocalDateTime fechaLlegadaLocal(LocalDateTime fechaSalida,
                                                   LocalTime horaLlegada,
                                                   Aeropuerto origen,
                                                   Aeropuerto destino) {
        int origenOffset = origen.getOffsetHorario() != null ? origen.getOffsetHorario() : 0;
        int destinoOffset = destino.getOffsetHorario() != null ? destino.getOffsetHorario() : 0;
        int depWall = toMinutes(fechaSalida.toLocalTime());
        int arrWall = toMinutes(horaLlegada);
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

    private static int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static String continentePorIcao(String code) {
        if (code == null || code.isBlank()) return "UNKNOWN";
        return switch (code.charAt(0)) {
            case 'S' -> "AM";
            case 'E', 'L', 'U' -> "EU";
            case 'O', 'V' -> "AS";
            default -> "UNKNOWN";
        };
    }
}
