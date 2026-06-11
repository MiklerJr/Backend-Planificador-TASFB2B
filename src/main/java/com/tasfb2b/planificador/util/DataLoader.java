package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.Vuelo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
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
            a.setLatitud(rs.getDouble("latitud"));
            a.setLongitud(rs.getDouble("longitud"));
            // Si tienes estos atributos en tu clase, descoméntalos:
            // a.setCiudad(rs.getString("ciudad"));
            // a.setHusoHorario(rs.getInt("huso_horario"));
            // a.setCapacidad(rs.getInt("capacidad_almacen"));
            return a;
        });

        // Llenamos la caché
        aeropuertoMapCache = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigo, a -> a));

        // 2. Cargamos Vuelos desde AWS
        String sqlVuelos = "SELECT id_vuelo, icao_origen, icao_destino, hora_salida, hora_llegada, capacidad_maxima FROM VUELO";
        vuelos = jdbcTemplate.query(sqlVuelos, (rs, rowNum) -> {
            Vuelo v = new Vuelo();
            v.setId(rowNum + 1);
            // Revisa si tu clase Vuelo guarda un String o el Objeto Aeropuerto entero:
            // Si guarda Strings:
            // v.setOrigen(rs.getString("icao_origen"));
            // v.setDestino(rs.getString("icao_destino"));
            // Si guarda Objetos:
            // v.setAeropuertoOrigen(aeropuertoMapCache.get(rs.getString("icao_origen")));
            // v.setAeropuertoDestino(aeropuertoMapCache.get(rs.getString("icao_destino")));
            
            // v.setHoraSalida(rs.getString("hora_salida"));
            // v.setHoraLlegada(rs.getString("hora_llegada"));
            // v.setCapacidad(rs.getInt("capacidad_maxima"));
            return v;
        });

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

        String sql = "SELECT id_envio, icao_origen, icao_destino, cantidad_maletas, fecha_hora_registro " +
                     "FROM ENVIO " +
                     "WHERE fecha_hora_registro >= ? AND fecha_hora_registro < ? " +
                     "ORDER BY fecha_hora_registro ASC";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Maleta m = new Maleta();
            String dbId = rs.getString("id_envio");
            String idOriginal = dbId.contains("-") ? dbId.substring(dbId.indexOf('-') + 1) : dbId;
            m.setId(Integer.parseInt(idOriginal));
            m.setAeropuertoOrigen(aeropuertoMapCache.get(rs.getString("icao_origen")));
            m.setAeropuertoDestino(aeropuertoMapCache.get(rs.getString("icao_destino")));
            m.setCantidad(rs.getInt("cantidad_maletas")); 
            m.setPlazo(48);
            m.setFechaHoraRegistro(rs.getTimestamp("fecha_hora_registro").toLocalDateTime());
            return m;
        }, Timestamp.valueOf(desde), Timestamp.valueOf(hasta));
    }

    public List<Maleta> getMaletasMuestra(int limite) {
        if (limite <= 0) return Collections.emptyList();
        String sql = "SELECT id_envio, icao_origen, icao_destino, cantidad_maletas, fecha_hora_registro " +
                     "FROM ENVIO ORDER BY fecha_hora_registro ASC LIMIT ?";
                     
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Maleta m = new Maleta();
            String dbId = rs.getString("id_envio");
            String idOriginal = dbId.contains("-") ? dbId.substring(dbId.indexOf('-') + 1) : dbId;
            m.setId(Integer.parseInt(idOriginal));
            m.setAeropuertoOrigen(aeropuertoMapCache.get(rs.getString("icao_origen")));
            m.setAeropuertoDestino(aeropuertoMapCache.get(rs.getString("icao_destino")));
            m.setCantidad(rs.getInt("cantidad_maletas"));
            m.setPlazo(48);
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
}