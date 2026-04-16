package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.model.Aeropuerto;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class AeropuertoLoader {

    public List<Aeropuerto> cargarAeropuertos() {

        List<Aeropuerto> aeropuertos = new ArrayList<>();

        try {


            InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");

            if (is == null) {
                throw new RuntimeException("No se encontró el archivo de aeropuertos");
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is));

            String linea;
            String continenteActual = null;

            while ((linea = br.readLine()) != null) {

                linea = linea.trim();

                // IGNORAR VACÍOS O SEPARADORES
                if (linea.isEmpty() || linea.startsWith("****")) {
                    continue;
                }

                // DETECTAR CONTINENTE
                if (linea.toLowerCase().contains("america") ||
                    linea.toLowerCase().contains("europe") ||
                    linea.toLowerCase().contains("asia") ||
                    linea.toLowerCase().contains("africa")) {

                    continenteActual = linea;
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

        } catch (Exception e) {
            throw new RuntimeException("Error cargando aeropuertos", e);
        }

        return aeropuertos;
    }

    // PARSER PRINCIPAL
    private Aeropuerto parseLinea(String linea, String continente) {

        try {

            // separar por múltiples espacios
            String[] parts = linea.split("\\s+");

            if (parts.length < 6) return null;

            Aeropuerto a = new Aeropuerto();

            a.setCodigo(parts[0]);
            a.setCiudad(parts[1]);
            a.setPais(parts[2]);
            a.setAbreviatura(parts[3]);

            a.setOffsetHorario(Integer.parseInt(parts[4]));
            a.setCapacidad(Integer.parseInt(parts[5]));

            a.setContinente(continente);

            // aquí lo dejamos null o lo mejoramos después
            // lat/lon normalmente vienen más complejos

            a.setActivo(true);

            return a;

        } catch (Exception e) {
            System.out.println("Error parseando línea: " + linea);
            return null;
        }
    }
}