package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Cliente;
import com.tasfb2b.planificador.model.Maleta;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class BaggageParser {

    public List<Maleta> parse(Path file, Aeropuerto origen,
                              Map<String, Aeropuerto> aeropuertoMap) throws IOException {
        List<String> lineas = FileUtils.leerLineasSeguro(file);
        List<Maleta> result = new ArrayList<>();

        for (String line : lineas) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] p = line.split("-");
            if (p.length < 7) continue;

            String idEnvio  = p[0];
            String dateStr  = p[1];
            int    hour     = Integer.parseInt(p[2]);
            int    minute   = Integer.parseInt(p[3]);
            String destCode = p[4];
            int    cantidad = Integer.parseInt(p[5]);
            String idCliente = p[6];

            Aeropuerto destino = aeropuertoMap.get(destCode);
            if (destino == null) continue;

            LocalDateTime fechaHoraRegistro = LocalDateTime.of(
                    LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE), // aaaammdd
                    LocalTime.of(hour, minute)
            );

            int plazo = origen.getContinente().equals(destino.getContinente()) ? 24 : 48;

            Cliente clienteRelacion = new Cliente();
            clienteRelacion.setId(Integer.parseInt(idCliente));

            Maleta maleta = new Maleta();
            maleta.setIdEnvio(idEnvio);
            maleta.setCantidad(cantidad); // Asignamos el número de maletas de este lote
            maleta.setAeropuertoOrigen(origen);
            maleta.setAeropuertoDestino(destino);
            maleta.setCliente(clienteRelacion);
            maleta.setFechaHoraRegistro(fechaHoraRegistro);
            maleta.setPlazo(plazo);

            result.add(maleta);
        }
        return result;
    }
}