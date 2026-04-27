package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.model.Aeropuerto;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class AeropuertoLoader {

    public List<Aeropuerto> cargarAeropuertos() {

        List<Aeropuerto> aeropuertos = new ArrayList<>();

        try {
            System.out.println("================================");
            System.out.println("Iniciando carga de aeropuertos...");
            InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");

            if (is == null) {
                throw new RuntimeException("No se encontró el archivo de aeropuertos");
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_16));

            String linea;
            String continenteActual = null;

            while ((linea = br.readLine()) != null) {

                linea = normalizarLinea(linea);

                // IGNORAR VACÍOS O SEPARADORES
                if (linea.isEmpty() || linea.startsWith("****")) {
                    continue;
                }

                // DETECTAR CONTINENTE
                if (esLineaContinente(linea)) {
                    continenteActual = extraerContinente(linea);
                    continue;
                }

                // IGNORAR HEADERS O LÍNEAS NO VÁLIDAS
                if (!Character.isLetterOrDigit(linea.charAt(0))) {
                    continue;
                }

                // PARSEAR LÍNEA DE AEROPUERTO
                Aeropuerto a = parseLinea(linea, continenteActual);

                if (a != null) {
                    aeropuertos.add(a);
                }
            }

            System.out.println(aeropuertos.size() + " aeropuertos cargados correctamente.");

        } catch (Exception e) {
            throw new RuntimeException("Error cargando aeropuertos", e);
        }

        return aeropuertos;
    }

    private String normalizarLinea(String linea) {
        return linea.replace("\uFEFF", "").trim();
    }

    private boolean esLineaContinente(String linea) {
        String s = linea.toLowerCase();
        return s.contains("america") || s.contains("europa") || s.contains("europe")
                || s.contains("asia") || s.contains("africa");
    }

    private String extraerContinente(String linea) {
        String continente = normalizarLinea(linea);

        int punto = continente.indexOf('.');
        if (punto >= 0) {
            continente = continente.substring(0, punto);
        }

        continente = continente.replaceAll("(?i)\\bGMT\\b.*$", "");
        continente = continente.replaceAll("(?i)\\bCAPACIDAD\\b.*$", "");
        continente = continente.replaceAll("\\s+", " ").trim();

        return continente;
    }

    // PARSER PRINCIPAL
    private Aeropuerto parseLinea(String linea, String continente) {

        try {
            linea = normalizarLinea(linea);

            String[] parts = linea.split("\\s+");

            String codigo = "";
            int offsetHorario = 0;
            int capacidad = 0;

            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) continue;

                if (p.matches("^[A-Z]{4}$")) {
                    codigo = p;
                }
            }

            if (codigo.isEmpty()) {
                return null;
            }

            int numbersFound = 0;
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) continue;
                if (p.equals(codigo)) continue;
                if (p.matches("^[A-Z].*")) continue;

                if (p.matches("^[+-]?\\d+$")) {
                    int num = Integer.parseInt(p);
                    numbersFound++;

                    if (numbersFound == 2) {
                        offsetHorario = num;
                    } else if (numbersFound == 3) {
                        capacidad = num;
                        break;
                    }
                }
            }

            if (capacidad == 0) {
                return null;
            }

            Aeropuerto a = new Aeropuerto();
            a.setCodigo(codigo);
            a.setOffsetHorario(offsetHorario);
            a.setCapacidad(capacidad);
            a.setContinente(continente);
            a.setActivo(true);


            return a;

        } catch (Exception e) {
            System.out.println("Error parseando línea: " + linea);
            return null;
        }
    }
}
