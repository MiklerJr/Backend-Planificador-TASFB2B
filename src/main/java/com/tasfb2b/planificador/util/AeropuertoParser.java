package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Aeropuerto;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class AeropuertoParser {

    // Detecta líneas como: "01   SKBO   Bogota   Colombia   bogo   -5   430   Latitude..."
    private static final Pattern LINE = Pattern.compile(
            "^\\s*(\\d{2})\\s+" +           // 1: número
                    "([A-Z]{4})\\s+" +              // 2: código ICAO
                    "(.+?)\\s{2,}" +                // 3: ciudad
                    "(.+?)\\s{2,}" +                // 4: país ← era (\\S+), ahora captura varias palabras
                    "(\\w{4})\\s+" +                // 5: código corto (bogo, quit...)
                    "([+-]\\d+)\\s+" +              // 6: GMT
                    "(\\d+)\\s+" +                  // 7: capacidad
                    "Latitude:\\s*([\\d°'\"NSns .]+)\\s+" +
                    "Longitude:\\s*([\\d°'\"EWew .]+)"
    );

    public List<Aeropuerto> parse(Path file) throws IOException {
        List<String> lineas = FileUtils.leerLineasSeguro(file);

        List<Aeropuerto> result = new ArrayList<>();

        for (String line : lineas) {
            Matcher m = LINE.matcher(line);
            if (m.find()) {
                String codigo = m.group(2).trim();
                Aeropuerto a = new Aeropuerto();
                a.setCodigo(codigo);
                a.setCiudad(m.group(3).trim());
                a.setPais(m.group(4).trim());
                a.setAbreviatura(m.group(5).trim());
                a.setOffsetHorario(Integer.parseInt(m.group(6)));
                a.setCapacidad(Integer.parseInt(m.group(7)));
                a.setContinente(continentePorIcao(codigo));
                a.setActivo(true);
                a.setLatitud(parseLatitud(m.group(8).trim()));
                a.setLongitud(parseLongitud(m.group(9).trim()));
                result.add(a);
            }
        }
        return result;
    }

    /**
     * Deriva el continente a partir del primer carácter del código ICAO.
     * Prefijos presentes en este dataset:
     *   S → Sudamérica (AM)
     *   E, L, U → Europa / Europa del Este (EU)
     *   O, V → Oriente Medio / Asia del Sur (AS)
     */
    private static String continentePorIcao(String code) {
        if (code == null || code.isEmpty()) return "UNKNOWN";
        return switch (code.charAt(0)) {
            case 'S'            -> "AM";
            case 'E', 'L', 'U' -> "EU";
            case 'O', 'V'       -> "AS";
            default             -> "UNKNOWN";
        };
    }
    // Convierte "12° 01' 19\" S" a -12.0219 (negativo si es S)
    private Double parseLatitud(String raw) {
        double val = parseDMS(raw);
        return raw.toUpperCase().contains("S") ? -val : val;
    }

    // Convierte "77° 06' 52\" W" a -77.0652 (negativo si es W)
    private Double parseLongitud(String raw) {
        double val = parseDMS(raw);
        return raw.toUpperCase().contains("W") ? -val : val;
    }

    private double parseDMS(String raw) {
        // Extrae todos los números del string
        Matcher m = Pattern.compile("(\\d+)").matcher(raw);
        int grados  = m.find() ? Integer.parseInt(m.group()) : 0;
        int minutos = m.find() ? Integer.parseInt(m.group()) : 0;
        int segundos = m.find() ? Integer.parseInt(m.group()) : 0;
        return grados + minutos / 60.0 + segundos / 3600.0;
    }
}