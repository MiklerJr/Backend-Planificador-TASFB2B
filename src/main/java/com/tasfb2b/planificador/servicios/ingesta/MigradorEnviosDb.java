package com.tasfb2b.planificador.servicios.ingesta;

import com.tasfb2b.planificador.dto.jobs.InyeccionEnviosRequest;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.utilidades.validador.ValidadorEnvio;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.springframework.jdbc.core.ConnectionCallback;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MigradorEnviosDb {

    private final JdbcTemplate jdbcTemplate;

    // Ingesta masiva de envíos vía COPY (streaming): una sola orden por archivo en vez de millones de
    // INSERT. La tabla se TRUNCA antes de cargar y el id_envio es único (ICAO-numero), así que no hace
    // falta ON CONFLICT. Formato text: campos TAB-separados, filas terminadas en '\n'.
    private static final String SQL_COPY_ENVIO =
            "COPY envio (id_envio, icao_origen, icao_destino, cantidad_maletas, id_cliente, fecha_hora_registro) FROM STDIN";
    private static final String SQL_VUELO =
            "INSERT INTO VUELO (id_vuelo, icao_origen, icao_destino, hora_salida, hora_llegada, capacidad_maxima, capacidad_maxima_original) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    private static final String SQL_AEROPUERTO =
            "INSERT INTO AEROPUERTO (icao, ciudad, pais, codigo_region, huso_horario, capacidad_almacen, capacidad_almacen_original, latitud, longitud, activo) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (icao) DO NOTHING";

    private static final int COPY_FLUSH_FILAS = 50_000;  // filas acumuladas antes de cada writeToCopy
    private static final int LOTE_VUELOS = 500;

    public MigradorEnviosDb(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertarAeropuertos(List<Aeropuerto> aeropuertos) {
        if (aeropuertos == null || aeropuertos.isEmpty()) return 0;
        List<Object[]> lote = new ArrayList<>(aeropuertos.size());
        for (Aeropuerto a : aeropuertos) {
            lote.add(new Object[]{ a.getCodigo(), a.getCiudad(), a.getPais(), a.getAbreviatura(),
                    a.getOffsetHorario(), a.getCapacidad(), a.getCapacidad(), a.getLatitud(), a.getLongitud(), a.isActivo() });
        }
        jdbcTemplate.batchUpdate(SQL_AEROPUERTO, lote);
        return lote.size();
    }

    public void migrarVuelos(String rutaArchivoVuelos) {
        try (BufferedReader br = new BufferedReader(new FileReader(rutaArchivoVuelos))) {
            int n = migrarVuelosDesde(br);
            log.info("Migración de VUELOS completada: {} filas", n);
        } catch (Exception e) {
            log.error("Error migrando vuelos desde {}: {}", rutaArchivoVuelos, e.getMessage());
        }
    }

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

            lote.add(new Object[]{ idVuelo, origen, destino, hSalida, hLlegada, capacidad, capacidad });
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

    public static String origenIcaoDeNombre(String nombreArchivo) {
        if (nombreArchivo == null) return null;
        String[] partes = nombreArchivo.split("_");
        return partes.length >= 3 ? partes[2] : null;
    }

    public int[] migrarEnviosDesde(Reader reader, String origenIcao) throws IOException {
        final BufferedReader br = (reader instanceof BufferedReader b) ? b : new BufferedReader(reader);
        final int[] contadores = new int[2]; // [0]=insertados, [1]=descartados
        try {
            jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                // La carga es reproducible; sin fsync por commit va mucho más rápido y no arriesga datos.
                try (Statement st = con.createStatement()) {
                    st.execute("SET synchronous_commit TO off");
                }
                // Idempotencia para el reintento: borra cualquier fila previa de este ICAO (id_envio =
                // ICAO-numero) antes de re-copiar. Rango sobre el índice de id_envio ('-'=0x2D, '.'=0x2E),
                // así usa el btree. Tras un TRUNCATE es no-op; tras un COPY parcial/colgado, limpia.
                try (PreparedStatement del = con.prepareStatement(
                        "DELETE FROM envio WHERE id_envio >= ? AND id_envio < ?")) {
                    del.setString(1, origenIcao + "-");
                    del.setString(2, origenIcao + ".");
                    del.executeUpdate();
                }
                CopyManager copyManager = new CopyManager(con.unwrap(BaseConnection.class));
                CopyIn copyIn = copyManager.copyIn(SQL_COPY_ENVIO);
                StringBuilder sb = new StringBuilder(1 << 20);
                int enBuffer = 0;
                try {
                    String linea;
                    while ((linea = br.readLine()) != null) {
                        String[] parts = linea.split("-");
                        if (parts.length < 7) continue;

                        String fechaRaw = parts[1].trim(), horaStr = parts[2].trim(), minStr = parts[3].trim(),
                               destino = parts[4].trim(), maletasStr = parts[5].trim(), clienteStr = parts[6].trim();
                        if (!ValidadorEnvio.camposObligatoriosPresentes(fechaRaw, horaStr, minStr, destino, maletasStr, clienteStr)) {
                            contadores[1]++;
                            continue;
                        }
                        int hh, mm, maletas, idCliente;
                        try {
                            hh = Integer.parseInt(horaStr);
                            mm = Integer.parseInt(minStr);
                            maletas = Integer.parseInt(maletasStr);
                            idCliente = Integer.parseInt(clienteStr);
                            if (fechaRaw.length() != 8) throw new NumberFormatException();
                        } catch (RuntimeException ex) {
                            contadores[1]++;
                            continue;
                        }
                        // Fila COPY text (id_envio = ICAO-numero): campos TAB-separados, timestamp "YYYY-MM-DD HH:MM:00".
                        // Los valores son ICAO/enteros/fecha: sin TAB, '\n' ni '\\', así que no requieren escape.
                        sb.append(origenIcao).append('-').append(parts[0].trim())
                          .append('\t').append(origenIcao)
                          .append('\t').append(destino)
                          .append('\t').append(maletas)
                          .append('\t').append(idCliente)
                          .append('\t').append(fechaRaw, 0, 4).append('-').append(fechaRaw, 4, 6)
                          .append('-').append(fechaRaw, 6, 8).append(' ');
                        if (hh < 10) sb.append('0');
                        sb.append(hh).append(':');
                        if (mm < 10) sb.append('0');
                        sb.append(mm).append(":00").append('\n');
                        contadores[0]++;

                        if (++enBuffer >= COPY_FLUSH_FILAS) {
                            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                            copyIn.writeToCopy(bytes, 0, bytes.length);
                            sb.setLength(0);
                            enBuffer = 0;
                        }
                    }
                    if (sb.length() > 0) {
                        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                        copyIn.writeToCopy(bytes, 0, bytes.length);
                    }
                    copyIn.endCopy();
                } catch (Exception e) {
                    if (copyIn.isActive()) {
                        try { copyIn.cancelCopy(); } catch (SQLException ignore) { /* ya abortado */ }
                    }
                    if (e instanceof IOException ioe) throw new UncheckedIOException(ioe);
                    if (e instanceof SQLException sqle) throw sqle;
                    throw new IllegalStateException("Fallo en COPY de envíos (" + origenIcao + ")", e);
                }
                return null;
            });
        } catch (UncheckedIOException uioe) {
            throw uioe.getCause();
        }
        return contadores;
    }

    public static List<InyeccionEnviosRequest.Item> parsearEnviosParaInyeccion(
            Reader reader, String origenIcao, int offsetOrigenHoras,
            String registrador, String sede) throws IOException {
        BufferedReader br = (reader instanceof BufferedReader b) ? b : new BufferedReader(reader);
        List<InyeccionEnviosRequest.Item> items = new ArrayList<>();
        String linea;
        while ((linea = br.readLine()) != null) {
            String[] parts = linea.split("-");
            if (parts.length < 7) continue;
            String fechaRaw = parts[1].trim(), horaStr = parts[2].trim(), minStr = parts[3].trim(),
                   destino = parts[4].trim(), maletasStr = parts[5].trim(), clienteStr = parts[6].trim();
            if (!ValidadorEnvio.camposObligatoriosPresentes(fechaRaw, horaStr, minStr, destino, maletasStr, clienteStr))
                continue;
            try {
                int hh = Integer.parseInt(horaStr), mm = Integer.parseInt(minStr);
                int maletas = Integer.parseInt(maletasStr), idCliente = Integer.parseInt(clienteStr);
                LocalDateTime registroLocal = LocalDateTime.of(
                        Integer.parseInt(fechaRaw.substring(0, 4)),
                        Integer.parseInt(fechaRaw.substring(4, 6)),
                        Integer.parseInt(fechaRaw.substring(6, 8)), hh, mm);
                InyeccionEnviosRequest.Item it = new InyeccionEnviosRequest.Item();
                it.setOrigen(origenIcao);
                it.setDestino(destino);
                it.setCantidad(maletas);
                it.setFechaHoraRegistro(registroLocal.minusHours(offsetOrigenHoras));
                it.setClienteId(idCliente);
                it.setRegistrador(registrador);
                it.setSede(sede);
                items.add(it);
            } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
                // descartar sin abortar
            }
        }
        return items;
    }
}
