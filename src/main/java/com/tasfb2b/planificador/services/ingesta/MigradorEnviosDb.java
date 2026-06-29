package com.tasfb2b.planificador.services.ingesta;

import com.tasfb2b.planificador.dto.jobs.InyeccionEnviosRequest;
import com.tasfb2b.planificador.model.dataset.Aeropuerto;
import com.tasfb2b.planificador.util.validator.EnvioValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Carga de datos crudos (aeropuertos, vuelos, envíos) a la BD. Originalmente una migración one-shot
 * desde archivos locales; la Fase 6B reusa los métodos {@code *Desde(Reader)} para la ingesta por
 * endpoint (subida multipart), sin depender de rutas en disco del servidor.
 *
 * <p>El parseo de envíos descarta líneas inválidas (RF03 vía {@link EnvioValidator}) sin abortar el
 * archivo. El INSERT usa {@code ON CONFLICT DO NOTHING} (idempotente); con reemplazo total las
 * tablas ya vienen vacías tras el TRUNCATE.
 */
@Slf4j
@Component
public class MigradorEnviosDb {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_ENVIO =
            "INSERT INTO ENVIO (id_envio, icao_origen, icao_destino, cantidad_maletas, id_cliente, fecha_hora_registro) "
          + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id_envio) DO NOTHING";
    private static final String SQL_VUELO =
            "INSERT INTO VUELO (id_vuelo, icao_origen, icao_destino, hora_salida, hora_llegada, capacidad_maxima) "
          + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    private static final String SQL_AEROPUERTO =
            "INSERT INTO AEROPUERTO (icao, ciudad, pais, codigo_region, huso_horario, capacidad_almacen, latitud, longitud, activo) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (icao) DO NOTHING";

    private static final int LOTE_ENVIOS = 10_000;
    private static final int LOTE_VUELOS = 500;

    public MigradorEnviosDb(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Aeropuertos ─────────────────────────────────────────────────────────────

    /** Inserta los aeropuertos ya parseados ({@code util/AeropuertoParser}). Devuelve el nº insertado. */
    public int insertarAeropuertos(List<Aeropuerto> aeropuertos) {
        if (aeropuertos == null || aeropuertos.isEmpty()) return 0;
        List<Object[]> lote = new ArrayList<>(aeropuertos.size());
        for (Aeropuerto a : aeropuertos) {
            lote.add(new Object[]{ a.getCodigo(), a.getCiudad(), a.getPais(), a.getAbreviatura(),
                    a.getOffsetHorario(), a.getCapacidad(), a.getLatitud(), a.getLongitud(), a.isActivo() });
        }
        jdbcTemplate.batchUpdate(SQL_AEROPUERTO, lote);
        return lote.size();
    }

    // ── Vuelos ──────────────────────────────────────────────────────────────────

    /** Carga vuelos desde un archivo local (uso legacy/one-shot). */
    public void migrarVuelos(String rutaArchivoVuelos) {
        try (BufferedReader br = new BufferedReader(new FileReader(rutaArchivoVuelos))) {
            int n = migrarVuelosDesde(br);
            log.info("Migración de VUELOS completada: {} filas", n);
        } catch (Exception e) {
            log.error("Error migrando vuelos desde {}: {}", rutaArchivoVuelos, e.getMessage());
        }
    }

    /** Parsea e inserta vuelos desde un {@link Reader} (ingesta por endpoint). Devuelve el nº de filas. */
    public int migrarVuelosDesde(Reader reader) throws IOException {
        BufferedReader br = (reader instanceof BufferedReader b) ? b : new BufferedReader(reader);
        List<Object[]> lote = new ArrayList<>();
        int total = 0;
        String linea;
        while ((linea = br.readLine()) != null) {
            linea = linea.trim();
            if (linea.isEmpty() || linea.contains("ORIG-DEST")) continue;
            linea = linea.replace("//", "");
            String[] parts = linea.split("[\\s-]+");
            if (parts.length < 4) continue;

            String origen = parts[0], destino = parts[1], hSalida = parts[2], hLlegada = parts[3];
            int capacidad = (parts.length >= 5 && parts[4].matches("\\d+")) ? Integer.parseInt(parts[4]) : 0;
            String idVuelo = origen + "-" + destino + "-" + hSalida.replace(":", "");

            lote.add(new Object[]{ idVuelo, origen, destino, hSalida, hLlegada, capacidad });
            if (lote.size() >= LOTE_VUELOS) {
                jdbcTemplate.batchUpdate(SQL_VUELO, lote);
                total += lote.size();
                lote.clear();
            }
        }
        if (!lote.isEmpty()) {
            jdbcTemplate.batchUpdate(SQL_VUELO, lote);
            total += lote.size();
        }
        return total;
    }

    // ── Envíos ──────────────────────────────────────────────────────────────────

    /** Carga todos los {@code _envios_<ICAO>_.txt} de un directorio local (uso legacy/one-shot). */
    public void migrarDirectorioCompleto(String rutaDirectorio) {
        File carpeta = new File(rutaDirectorio);
        File[] archivos = carpeta.listFiles((dir, name) -> name.endsWith(".txt") && name.startsWith("_envios_"));
        if (archivos == null || archivos.length == 0) {
            log.warn("No se encontraron archivos de envíos en: {}", rutaDirectorio);
            return;
        }
        for (File archivo : archivos) {
            String origenIcao = origenIcaoDeNombre(archivo.getName());
            if (origenIcao == null) continue;
            try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
                int[] r = migrarEnviosDesde(br, origenIcao);
                log.info("Migrado {}: {} insertados, {} descartados (RF03)", archivo.getName(), r[0], r[1]);
            } catch (Exception e) {
                log.error("Error procesando {}: {}", archivo.getName(), e.getMessage());
            }
        }
    }

    /** ICAO de origen embebido en {@code _envios_<ICAO>_.txt} ({@code partes[2]}); null si no casa. */
    public static String origenIcaoDeNombre(String nombreArchivo) {
        if (nombreArchivo == null) return null;
        String[] partes = nombreArchivo.split("_");
        return partes.length >= 3 ? partes[2] : null;
    }

    /**
     * Parsea e inserta envíos desde un {@link Reader}, con el {@code origenIcao} (derivado del nombre
     * del archivo). Devuelve {@code [insertados, descartados]}. RF03: descarta líneas inválidas sin
     * abortar.
     */
    public int[] migrarEnviosDesde(Reader reader, String origenIcao) throws IOException {
        BufferedReader br = (reader instanceof BufferedReader b) ? b : new BufferedReader(reader);
        List<Object[]> batchArgs = new ArrayList<>();
        int insertados = 0, descartados = 0;
        String linea;
        while ((linea = br.readLine()) != null) {
            String[] parts = linea.split("-");
            if (parts.length < 7) continue;

            String fechaRaw = parts[1].trim(), horaStr = parts[2].trim(), minStr = parts[3].trim(),
                   destino = parts[4].trim(), maletasStr = parts[5].trim(), clienteStr = parts[6].trim();
            // RF03: id opcional; el resto de campos obligatorios deben estar presentes.
            if (!EnvioValidator.camposObligatoriosPresentes(fechaRaw, horaStr, minStr, destino, maletasStr, clienteStr)) {
                descartados++;
                continue;
            }
            try {
                String id = origenIcao + "-" + parts[0].trim();
                int hh = Integer.parseInt(horaStr), mm = Integer.parseInt(minStr);
                int maletas = Integer.parseInt(maletasStr), idCliente = Integer.parseInt(clienteStr);
                // Timestamp PostgreSQL (YYYY-MM-DD HH:MM:SS) desde la fecha YYYYMMDD + hh:mm.
                String ts = String.format("%s-%s-%s %02d:%02d:00",
                        fechaRaw.substring(0, 4), fechaRaw.substring(4, 6), fechaRaw.substring(6, 8), hh, mm);
                batchArgs.add(new Object[]{ id, origenIcao, destino, maletas, idCliente, Timestamp.valueOf(ts) });
            } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
                descartados++;
                continue;
            }
            if (batchArgs.size() == LOTE_ENVIOS) {
                jdbcTemplate.batchUpdate(SQL_ENVIO, batchArgs);
                insertados += batchArgs.size();
                batchArgs.clear();
            }
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(SQL_ENVIO, batchArgs);
            insertados += batchArgs.size();
        }
        return new int[]{ insertados, descartados };
    }

    /**
     * E1 — Operación día a día EN VIVO: parsea envíos de un {@link Reader} a
     * {@link InyeccionEnviosRequest.Item} <b>SIN tocar la BD</b> (a diferencia de
     * {@link #migrarEnviosDesde}). Mismo formato del dataset
     * {@code id-YYYYMMDD-HH-MM-DESTINO-cantidad-idCliente}; el {@code origenIcao} viene del nombre del
     * archivo ({@link #origenIcaoDeNombre}) o del form. Los tiempos del TXT se interpretan en <b>UTC</b>
     * (el backend es 100% UTC; el front ya convierte). Descarta líneas inválidas (RF03) sin abortar y
     * aplica {@code registrador}/{@code sede} a todos los ítems. La validación de negocio (ICAO
     * existente, origen≠destino, cantidad>0) la hace después {@code solicitarInyeccionEnvios}.
     */
    public static List<InyeccionEnviosRequest.Item> parsearEnviosParaInyeccion(
            Reader reader, String origenIcao, String registrador, String sede) throws IOException {
        BufferedReader br = (reader instanceof BufferedReader b) ? b : new BufferedReader(reader);
        List<InyeccionEnviosRequest.Item> items = new ArrayList<>();
        String linea;
        while ((linea = br.readLine()) != null) {
            String[] parts = linea.split("-");
            if (parts.length < 7) continue;
            String fechaRaw = parts[1].trim(), horaStr = parts[2].trim(), minStr = parts[3].trim(),
                   destino = parts[4].trim(), maletasStr = parts[5].trim(), clienteStr = parts[6].trim();
            if (!EnvioValidator.camposObligatoriosPresentes(fechaRaw, horaStr, minStr, destino, maletasStr, clienteStr))
                continue;
            try {
                int hh = Integer.parseInt(horaStr), mm = Integer.parseInt(minStr);
                int maletas = Integer.parseInt(maletasStr), idCliente = Integer.parseInt(clienteStr);
                LocalDateTime registroUtc = LocalDateTime.of(
                        Integer.parseInt(fechaRaw.substring(0, 4)),
                        Integer.parseInt(fechaRaw.substring(4, 6)),
                        Integer.parseInt(fechaRaw.substring(6, 8)), hh, mm);
                InyeccionEnviosRequest.Item it = new InyeccionEnviosRequest.Item();
                it.setOrigen(origenIcao);
                it.setDestino(destino);
                it.setCantidad(maletas);
                it.setFechaHoraRegistro(registroUtc);
                it.setClienteId(idCliente);
                it.setRegistrador(registrador);
                it.setSede(sede);
                items.add(it);
            } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
                // línea malformada → descartar sin abortar
            }
        }
        return items;
    }
}
