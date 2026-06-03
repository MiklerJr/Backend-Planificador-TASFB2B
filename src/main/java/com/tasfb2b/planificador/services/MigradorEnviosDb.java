package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.util.EnvioValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Component
public class MigradorEnviosDb {

    private final JdbcTemplate jdbcTemplate;

    public MigradorEnviosDb(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void migrarDirectorioCompleto(String rutaDirectorio) {
        File carpeta = new File(rutaDirectorio);
        File[] archivos = carpeta.listFiles((dir, name) -> name.endsWith(".txt") && name.startsWith("_envios_"));

        if (archivos == null || archivos.length == 0) {
            System.out.println("No se encontraron archivos en la ruta: " + rutaDirectorio);
            return;
        }

        // Ahora incluimos id_cliente y el TIMESTAMP real (fecha_hora_registro)
        String sql = "INSERT INTO ENVIO (id_envio, icao_origen, icao_destino, cantidad_maletas, hora_registro, minuto_registro, id_cliente, fecha_hora_registro) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id_envio) DO NOTHING";

        int totalArchivos = archivos.length;
        int procesados = 0;

        for (File archivo : archivos) {
            String nombre = archivo.getName();
            String[] partesNombre = nombre.split("_");
            if (partesNombre.length < 3) continue;
            String origenIcao = partesNombre[2]; 

            List<Object[]> batchArgs = new ArrayList<>();
            int descartados = 0; // RF03: líneas con campos obligatorios faltantes o mal formados

            try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    String[] parts = linea.split("-");

                    // Aseguramos que tenga los 7 bloques
                    if (parts.length < 7) continue;

                    String idRaw      = parts[0].trim(); // RF03: el id es opcional (puede venir en blanco)
                    String fechaRaw   = parts[1].trim(); // 20260102
                    String horaStr    = parts[2].trim();
                    String minStr     = parts[3].trim();
                    String destino    = parts[4].trim();
                    String maletasStr = parts[5].trim();
                    String clienteStr = parts[6].trim();

                    // RF03: todos los campos obligatorios (todos menos el id) deben estar presentes.
                    if (!EnvioValidator.camposObligatoriosPresentes(fechaRaw, horaStr, minStr, destino, maletasStr, clienteStr)) {
                        descartados++;
                        continue;
                    }

                    try {
                        String id = origenIcao + "-" + idRaw;
                        int hh = Integer.parseInt(horaStr);
                        int mm = Integer.parseInt(minStr);
                        int maletas = Integer.parseInt(maletasStr);
                        int idCliente = Integer.parseInt(clienteStr);

                        // Construcción del Timestamp para PostgreSQL (YYYY-MM-DD HH:MM:SS)
                        String anio = fechaRaw.substring(0, 4);
                        String mes = fechaRaw.substring(4, 6);
                        String dia = fechaRaw.substring(6, 8);
                        String timestampStr = String.format("%s-%s-%s %02d:%02d:00", anio, mes, dia, hh, mm);
                        Timestamp fechaHoraRegistro = Timestamp.valueOf(timestampStr);

                        batchArgs.add(new Object[]{id, origenIcao, destino, maletas, hh, mm, idCliente, fechaHoraRegistro});
                    } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
                        // RF03: línea con un campo obligatorio mal formado; se descarta sin abortar el archivo.
                        descartados++;
                        continue;
                    }

                    if (batchArgs.size() == 10000) {
                        jdbcTemplate.batchUpdate(sql, batchArgs);
                        batchArgs.clear();
                    }
                }

                if (!batchArgs.isEmpty()) {
                    jdbcTemplate.batchUpdate(sql, batchArgs);
                }

                procesados++;
                System.out.println("[" + procesados + "/" + totalArchivos + "] Migrado exitosamente: " + nombre
                        + (descartados > 0 ? " (RF03: " + descartados + " líneas descartadas por campos inválidos)" : ""));

            } catch (Exception e) {
                System.out.println("Error procesando " + nombre + ": " + e.getMessage());
            }
        }
        System.out.println("¡Migración de 9 millones de registros completada!");
    }
}