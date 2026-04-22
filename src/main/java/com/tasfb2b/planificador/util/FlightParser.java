package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Vuelo;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class FlightParser {

    public List<Vuelo> parse(Path file, Map<String, Aeropuerto> aeropuertoMap) throws IOException {
        List<Vuelo> result = new ArrayList<>();
        LocalDate hoy = LocalDate.now(); // Precaución: Esto ancla los vuelos al día en que se ejecuta el código

        for (String line : FileUtils.leerLineasSeguro(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;

            String[] parts = line.split("-");
            if (parts.length < 5) {
                System.out.println("❌ Vuelo ignorado (Formato incorrecto): " + line);
                continue;
            }

            String origenCodigo = parts[0];
            String destCodigo   = parts[1];

            Aeropuerto origen  = aeropuertoMap.get(origenCodigo);
            Aeropuerto destino = aeropuertoMap.get(destCodigo);

            if (origen == null || destino == null) {
                System.out.println("❌ Vuelo ignorado (Aeropuerto no existe en el Map): " + origenCodigo + " o " + destCodigo);
                continue;
            }

            String[] horaSalidaStr = parts[2].split(":");
            String[] horaLlegadaStr = parts[3].split(":");

            if (horaSalidaStr.length < 2 || horaLlegadaStr.length < 2) {
                System.out.println("❌ Vuelo ignorado (Hora incorrecta): " + parts[2] + " o " + parts[3]);
                continue;
            }

            try {
                int salidaHora    = Integer.parseInt(horaSalidaStr[0]);
                int salidaMinuto  = Integer.parseInt(horaSalidaStr[1]);
                int llegadaHora   = Integer.parseInt(horaLlegadaStr[0]);
                int llegadaMinuto = Integer.parseInt(horaLlegadaStr[1]);
                int capacidad     = Integer.parseInt(parts[4].trim());

                LocalDateTime fechaSalida  = LocalDateTime.of(hoy, LocalTime.of(salidaHora, salidaMinuto));
                LocalDateTime fechaLlegada = LocalDateTime.of(hoy, LocalTime.of(llegadaHora % 24, llegadaMinuto));

                if (fechaLlegada.isBefore(fechaSalida)) {
                    fechaLlegada = fechaLlegada.plusDays(1);
                }

                Vuelo vuelo = new Vuelo();
                vuelo.setCapacidad(capacidad);
                vuelo.setOrigen(origenCodigo);
                vuelo.setDestino(destCodigo);
                vuelo.setFechaHoraSalida(fechaSalida);
                vuelo.setFechaHoraLlegada(fechaLlegada);
                vuelo.setAeropuertoOrigen(origen);
                vuelo.setAeropuertoDestino(destino);
                result.add(vuelo);

            } catch (Exception e) {
                System.out.println("❌ Vuelo ignorado (Error procesando datos numéricos): " + line + " -> " + e.getMessage());
            }
        }
        return result;
    }
}