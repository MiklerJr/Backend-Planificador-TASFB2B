package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Envio;
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
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DataLoader {

    private final JdbcTemplate jdbcTemplate;

    private List<Aeropuerto> aeropuertos = new ArrayList<>();
    private List<Vuelo> vuelos = new ArrayList<>();

    // Caché para mapear rápido los códigos ICAO a objetos Aeropuerto desde la BD
    private Map<String, Aeropuerto> aeropuertoMapCache;

    // Eje de tiempo UTC del cursor de ventanas. La columna ENVIO.fecha_hora_registro guarda la
    // hora LOCAL del origen; el cursor del motor avanza en UTC, así que primera/última ventana y
    // el filtrado de demanda se expresan en registroUtc = fecha_hora_registro − offset(origen).
    // El offset de husos está acotado a ±maxOffsetAbsHoras: permite ensanchar el filtro local sin
    // tocar la BD. Ambos campos se calculan una sola vez en load().
    private int maxOffsetAbsHoras = 0;
    private LocalDateTime primeraVentanaUtc;
    private LocalDateTime ultimaVentanaUtc;

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

        // 3. Eje UTC del cursor: máximo offset absoluto de husos (cota del ensanchamiento del
        //    filtro local) y rango [primera, última] ventana ya en UTC. Se calcula una sola vez.
        maxOffsetAbsHoras = aeropuertos.stream()
                .map(Aeropuerto::getOffsetHorario)
                .filter(Objects::nonNull)
                .mapToInt(Math::abs)
                .max()
                .orElse(0);
        calcularRangoUtcDataset();

        // 4. Resumen consultado directamente a PostgreSQL
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

    /** Primera ventana del dataset en UTC = mínimo registroUtc (cacheado en {@link #load()}). */
    public LocalDateTime getPrimeraVentana() {
        return primeraVentanaUtc;
    }

    /** Última ventana del dataset en UTC = máximo registroUtc (cacheado en {@link #load()}). */
    public LocalDateTime getUltimaVentana() {
        return ultimaVentanaUtc;
    }

    /**
     * Calcula el rango UTC del dataset (mín/máx de {@code registroUtc = fecha_hora_registro −
     * offset(origen)}) sin escanear por ventana ni materializar columnas: agrega por origen
     * (≈30 filas) y aplica el offset en RAM. Una sola pasada al arranque.
     */
    private void calcularRangoUtcDataset() {
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

    /**
     * Demanda de la ventana UTC {@code [desdeUtc, hastaUtc)}. El cursor del motor avanza en UTC,
     * pero {@code ENVIO.fecha_hora_registro} es hora LOCAL del origen. Como el offset de husos está
     * acotado a ±{@link #maxOffsetAbsHoras}, todo envío con {@code registroUtc} en la ventana tiene
     * su hora local en {@code [desdeUtc − off, hastaUtc + off)}: se consulta ese rango ensanchado
     * (índice local existente, sin tocar la BD) y se descarta en RAM lo que, tras restar el offset
     * real del origen, cae fuera de la ventana UTC. Así los bloques resultan UTC contiguos.
     */
    public List<Envio> getMaletasEnRango(LocalDateTime desdeUtc, LocalDateTime hastaUtc) {
        if (desdeUtc == null || hastaUtc == null || !desdeUtc.isBefore(hastaUtc)) {
            return Collections.emptyList();
        }

        // Ensanchamiento del filtro local por la cota de husos (no toca el esquema: usa el índice
        // existente sobre fecha_hora_registro).
        LocalDateTime desdeLocal = desdeUtc.minusHours(maxOffsetAbsHoras);
        LocalDateTime hastaLocal = hastaUtc.plusHours(maxOffsetAbsHoras);

        // RF02: se excluyen los envíos cuyo aeropuerto de origen y destino son el mismo.
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
            // Fase 4: conservar el id_envio COMPLETO ("ICAO-num") como clave del envio. Sin esto,
            // LuggageBatch.id quedaba null y rompia la contabilidad de origenAdmitidos y el conteo
            // de auditoria (batchAuditKey). AlgorithmMapper.mapToBatches lo lee con getIdEnvio().
            m.setIdEnvio(dbId);
            Aeropuerto origen = aeropuertoMapCache.get(rs.getString("icao_origen"));
            m.setAeropuertoOrigen(origen);
            m.setAeropuertoDestino(aeropuertoMapCache.get(rs.getString("icao_destino")));

            // Extraemos como Int en lugar de Long
            m.setCantidad(rs.getInt("cantidad_maletas"));
            // El plazo (SLA en horas) deriva del tipo de envío: 24h intracontinental, 48h intercontinental.
            m.setTipoEnvio(TipoEnvio.derivar(m.getAeropuertoOrigen(), m.getAeropuertoDestino()));
            m.setPlazo(m.getTipoEnvio() == TipoEnvio.INTRACONTINENTAL ? 24 : 48);

            // Se conserva el registro LOCAL: mapToBatches recalcula readyTimeUtc = registro − offset
            // y el DTO reconstruye registroLocal/registroUtc a partir de ahí.
            m.setFechaHoraRegistro(rs.getTimestamp("fecha_hora_registro").toLocalDateTime());

            // Filtro fino por registroUtc real: descarta lo traído por el ensanchamiento que no
            // pertenece a la ventana UTC (se limpian los null tras la consulta).
            int off = (origen != null && origen.getOffsetHorario() != null) ? origen.getOffsetHorario() : 0;
            if (!registroEnVentanaUtc(m.getFechaHoraRegistro(), off, desdeUtc, hastaUtc)) {
                return null;
            }
            return m;
        }, Timestamp.valueOf(desdeLocal), Timestamp.valueOf(hastaLocal));

        maletas.removeIf(Objects::isNull);
        return maletas;
    }

    /**
     * ¿El envío pertenece a la ventana UTC {@code [desdeUtc, hastaUtc)} según su instante UTC real
     * ({@code registroLocal − offsetHoras})? Frontera inferior inclusiva y superior exclusiva, de
     * modo que ventanas UTC adyacentes particionan la demanda sin solapes ni huecos — la base de
     * que los bloques publicados sean UTC contiguos. Visible a nivel de paquete para pruebas.
     */
    static boolean registroEnVentanaUtc(LocalDateTime registroLocal, int offsetHoras,
                                        LocalDateTime desdeUtc, LocalDateTime hastaUtc) {
        LocalDateTime registroUtc = registroLocal.minusHours(offsetHoras);
        return !registroUtc.isBefore(desdeUtc) && registroUtc.isBefore(hastaUtc);
    }

    public List<Envio> getMaletasMuestra(int limite) {
        if (limite <= 0) return Collections.emptyList();
        // RF02: se excluyen los envíos cuyo aeropuerto de origen y destino son el mismo.
        String sql = "SELECT id_envio, icao_origen, icao_destino, cantidad_maletas, fecha_hora_registro " +
                     "FROM ENVIO WHERE icao_origen <> icao_destino " +
                     "ORDER BY fecha_hora_registro ASC LIMIT ?";
                     
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Envio m = new Envio();
            String dbId = rs.getString("id_envio");
            String idOriginal = dbId.contains("-") ? dbId.substring(dbId.indexOf('-') + 1) : dbId;
            m.setId(Integer.parseInt(idOriginal));
            // Fase 4: conservar el id_envio COMPLETO ("ICAO-num") como clave del envio. Sin esto,
            // LuggageBatch.id quedaba null y rompia la contabilidad de origenAdmitidos y el conteo
            // de auditoria (batchAuditKey). AlgorithmMapper.mapToBatches lo lee con getIdEnvio().
            m.setIdEnvio(dbId);
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

    /** Aeropuerto por ICAO desde el cache cargado al arranque (offset, continente, etc.). Null si no existe. */
    public Aeropuerto getAeropuerto(String icao) {
        return icao == null ? null : aeropuertoMapCache.get(icao);
    }

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
