package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.TipoEnvio;
import com.tasfb2b.planificador.model.Vuelo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
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

    @Value("${data.airports.file}")
    private String airportsFile;

    @Value("${data.flights.file}")
    private String flightsFile;

    private final AeropuertoParser aeropuertoParser;
    private final FlightParser vueloParser;
    private final JdbcTemplate jdbcTemplate; // Agregamos la conexión a PostgreSQL

    private List<Aeropuerto> aeropuertos = new ArrayList<>();
    private List<Vuelo> vuelos = new ArrayList<>();
    
    // Caché para mapear rápido los códigos ICAO a objetos Aeropuerto desde la BD
    private Map<String, Aeropuerto> aeropuertoMapCache; 

    // Hemos eliminado el BaggageParser del constructor porque ya no leeremos .txt
    public DataLoader(AeropuertoParser aeropuertoParser,
                      FlightParser vueloParser,
                      JdbcTemplate jdbcTemplate) {
        this.aeropuertoParser = aeropuertoParser;
        this.vueloParser = vueloParser;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void load() throws IOException {
        // 1. Cargamos Aeropuertos y Vuelos en RAM (son livianos y necesarios para grafos)
        aeropuertos = aeropuertoParser.parse(Path.of(airportsFile));
        aeropuertoMapCache = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigo, a -> a));

        vuelos = vueloParser.parse(Path.of(flightsFile), aeropuertoMapCache);

        // 2. Resumen consultado directamente a PostgreSQL
        log.info("=================================================");
        log.info("RESUMEN DE DATOS (AEROPUERTOS/VUELOS EN RAM, ENVÍOS EN POSTGRESQL)");
        log.info("Aeropuertos : {}", aeropuertos.size());
        log.info("Vuelos      : {}", vuelos.size());
        
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
            log.warn("No se pudo obtener el resumen de envíos. ¿Está PostgreSQL apagado o la tabla vacía? {}", e.getMessage());
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

    /**
     * Ahora la base de datos hace el trabajo pesado de buscar por rango de fechas
     * y ordenar, devolviendo solo el fragmento exacto que el ALNS/ACO necesita.
     */
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
            
            // Convertimos el String de PostgreSQL al Integer que pide tu clase
            // Leemos el ID de la base de datos (ej. "SGAS-000000001")
            String dbId = rs.getString("id_envio");
            // Le cortamos el prefijo para quedarnos solo con el número original
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
        String sql = "SELECT id_envio, icao_origen, icao_destino, cantidad_maletas, fecha_hora_registro " +
                     "FROM ENVIO ORDER BY fecha_hora_registro ASC LIMIT ?";
                     
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Maleta m = new Maleta();
            
            // Mismo cambio aquí
            // Leemos el ID de la base de datos (ej. "SGAS-000000001")
            String dbId = rs.getString("id_envio");
            // Le cortamos el prefijo para quedarnos solo con el número original
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
        return getTotalMaletas(); // Compatibilidad legacy
    }

    public long getTotalMaletasIndividuales() {
        Long sum = jdbcTemplate.queryForObject("SELECT SUM(cantidad_maletas) FROM ENVIO", Long.class);
        return sum != null ? sum : 0L;
    }

    public List<Aeropuerto> getAeropuertos() { return aeropuertos; }
    public List<Vuelo>      getVuelos()      { return vuelos; }
}