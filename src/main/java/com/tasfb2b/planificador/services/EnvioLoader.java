package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.EnvioDTO;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class EnvioLoader {

    public List<EnvioDTO> cargarEnvios(String origenICAO) {
        List<EnvioDTO> envios = new ArrayList<>();

        String filename = "data/_envios_preliminar_/_envios_" + origenICAO + "_.txt";

        try {
            InputStream is = getClass()
                    .getClassLoader()
                    .getResourceAsStream(filename);

            if (is == null) {
                System.out.println("Archivo no encontrado: " + filename);
                return envios;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is));

            String linea;
            while ((linea = br.readLine()) != null) {
                EnvioDTO envio = parseLinea(linea);
                if (envio != null) {
                    envios.add(envio);
                }
            }

            br.close();

        } catch (Exception e) {
            System.out.println("Error cargando envíos: " + e.getMessage());
        }

        return envios;
    }

    private EnvioDTO parseLinea(String linea) {
        try {
            String[] parts = linea.split("-");

            if (parts.length < 6) {
                return null;
            }

            String id = parts[0].trim();
            int hh = Integer.parseInt(parts[2].trim());
            int mm = Integer.parseInt(parts[3].trim());
            String destino = parts[4].trim();
            int maletas = Integer.parseInt(parts[5].trim());

            return new EnvioDTO(id, destino, maletas, hh, mm);

        } catch (Exception e) {
            return null;
        }
    }
}
