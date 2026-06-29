package com.tasfb2b.planificador.util.parser;

import com.tasfb2b.planificador.model.dataset.Aeropuerto;
import com.tasfb2b.planificador.model.dataset.Cliente;
import com.tasfb2b.planificador.model.dataset.Envio;
import com.tasfb2b.planificador.model.dataset.TipoEnvio;
import com.tasfb2b.planificador.util.FileUtils;
import com.tasfb2b.planificador.util.validator.EnvioValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BaggageParser {

    public List<Envio> parse(Path file, Aeropuerto origen,
                              Map<String, Aeropuerto> aeropuertoMap) throws IOException {
        List<String> lineas = FileUtils.leerLineasSeguro(file);
        List<Envio> result = new ArrayList<>();
        int descartadosMismoAeropuerto = 0;    // RF02: envíos con origen == destino
        int descartadosCamposIncompletos = 0;  // RF03: envíos con campos obligatorios faltantes o mal formados

        for (String line : lineas) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] p = line.split("-");
            if (p.length < 7) continue;

            String idEnvio   = p[0].trim();   // RF03: el id es opcional (puede venir en blanco)
            String dateStr   = p[1].trim();
            String horaStr   = p[2].trim();
            String minStr    = p[3].trim();
            String destCode  = p[4].trim();
            String cantStr   = p[5].trim();
            String idCliente = p[6].trim();

            // RF03: todos los campos obligatorios (todos menos el id) deben estar presentes.
            if (!EnvioValidator.camposObligatoriosPresentes(dateStr, horaStr, minStr, destCode, cantStr, idCliente)) {
                descartadosCamposIncompletos++;
                continue;
            }

            Aeropuerto destino = aeropuertoMap.get(destCode);
            if (destino == null) continue;

            // RF02: el origen y el destino de un envío no pueden ser el mismo aeropuerto.
            if (EnvioValidator.esMismoAeropuerto(origen.getCodigo(), destino.getCodigo())) {
                descartadosMismoAeropuerto++;
                continue;
            }

            // RF03: los campos numéricos y la fecha deben estar bien formados.
            int hour, minute, cantidad, clienteId;
            LocalDateTime fechaHoraRegistro;
            try {
                hour      = Integer.parseInt(horaStr);
                minute    = Integer.parseInt(minStr);
                cantidad  = Integer.parseInt(cantStr);
                clienteId = Integer.parseInt(idCliente);
                fechaHoraRegistro = LocalDateTime.of(
                        LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE), // aaaammdd
                        LocalTime.of(hour, minute)
                );
            } catch (IllegalArgumentException | DateTimeException ex) {
                descartadosCamposIncompletos++;
                continue;
            }

            TipoEnvio tipoEnvio = TipoEnvio.derivar(origen, destino);
            int plazo = tipoEnvio == TipoEnvio.INTRACONTINENTAL ? 24 : 48;

            Cliente clienteRelacion = new Cliente();
            clienteRelacion.setId(clienteId);

            Envio maleta = new Envio();
            maleta.setIdEnvio(idEnvio);
            maleta.setCantidad(cantidad); // Asignamos el número de maletas de este lote
            maleta.setAeropuertoOrigen(origen);
            maleta.setAeropuertoDestino(destino);
            maleta.setCliente(clienteRelacion);
            maleta.setFechaHoraRegistro(fechaHoraRegistro);
            maleta.setPlazo(plazo);
            maleta.setTipoEnvio(tipoEnvio);

            result.add(maleta);
        }
        if (descartadosMismoAeropuerto > 0) {
            log.warn("RF02 [{}]: {} envíos descartados por tener origen == destino.",
                    file.getFileName(), descartadosMismoAeropuerto);
        }
        if (descartadosCamposIncompletos > 0) {
            log.warn("RF03 [{}]: {} envíos descartados por campos obligatorios faltantes o mal formados.",
                    file.getFileName(), descartadosCamposIncompletos);
        }
        return result;
    }
}