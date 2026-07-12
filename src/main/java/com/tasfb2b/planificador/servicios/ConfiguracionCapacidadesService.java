package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.analizador.AnalizadorVuelos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ConfiguracionCapacidadesService {

    private final JdbcTemplate jdbcTemplate;
    private final CargadorDatos cargadorDatos;
    private final MotorGrafoCache motorCache;
    private final RegistroJobs registroJobs;

    public ConfiguracionCapacidadesService(JdbcTemplate jdbcTemplate,
                                           CargadorDatos cargadorDatos,
                                           MotorGrafoCache motorCache,
                                           RegistroJobs registroJobs) {
        this.jdbcTemplate = jdbcTemplate;
        this.cargadorDatos = cargadorDatos;
        this.motorCache = motorCache;
        this.registroJobs = registroJobs;
    }

    public boolean actualizarCapacidadAeropuerto(String icao, int valor) {
        validarValor(valor);
        Grafo grafo = motorCache.grafoSiExiste();

        if (registroJobs.haySimulacionEnCurso()) {
            Nodo nodo = grafo != null ? grafo.nodos.get(icao) : null;
            if (nodo == null) return false;
            nodo.capacidad = valor;
            nodo.capacidadAlmacen = valor;
            log.info("Capacidad de almacén {} → {} (EN CALIENTE, solo grafo del run).", icao, valor);
            return true;
        }


        int filas = jdbcTemplate.update(
                "UPDATE aeropuerto SET capacidad_almacen = ? WHERE icao = ?", valor, icao);
        if (filas == 0) return false;
        Aeropuerto a = cargadorDatos.getAeropuerto(icao);
        if (a != null) a.setCapacidad(valor);
        if (grafo != null) {
            Nodo nodo = grafo.nodos.get(icao);
            if (nodo != null) {
                nodo.capacidad = valor;
                nodo.capacidadAlmacen = valor;
            }
        }
        log.info("Capacidad de almacén {} → {} (EN FRÍO, BD+RAM+grafo).", icao, valor);
        return true;
    }

    public boolean actualizarCapacidadVuelo(String idVuelo, int valor) {
        validarValor(valor);
        String idDb = idVuelo.replace(":", "");
        Grafo grafo = motorCache.grafoSiExiste();

        if (registroJobs.haySimulacionEnCurso()) {
            int i = indiceVuelo(idDb);
            if (i < 0) return false;
            if (grafo != null && i < grafo.aristas.size()) grafo.aristas.get(i).capacidad = valor;
            log.info("Capacidad de vuelo {} → {} (EN CALIENTE, solo grafo del run).", idDb, valor);
            return true;
        }

        int filas = jdbcTemplate.update(
                "UPDATE vuelo SET capacidad_maxima = ? WHERE id_vuelo = ?", valor, idDb);
        if (filas == 0) return false;
        int i = indiceVuelo(idDb);
        if (i >= 0) {
            cargadorDatos.getVuelos().get(i).setCapacidad(valor);
            if (grafo != null && i < grafo.aristas.size()) grafo.aristas.get(i).capacidad = valor;
        }
        log.info("Capacidad de vuelo {} → {} (EN FRÍO, BD+RAM+grafo).", idDb, valor);
        return true;
    }

    public void resincronizarCapacidadesConBaselineFrio() {
        Grafo grafo = motorCache.grafoSiExiste();
        if (grafo == null) return;

        for (Aeropuerto a : cargadorDatos.getAeropuertos()) {
            if (a.getCapacidad() == null) continue;
            Nodo nodo = grafo.nodos.get(a.getCodigo());
            if (nodo != null && (nodo.capacidad != a.getCapacidad() || nodo.capacidadAlmacen != a.getCapacidad())) {
                nodo.capacidad = a.getCapacidad();
                nodo.capacidadAlmacen = a.getCapacidad();
            }
        }
        List<Vuelo> vuelos = cargadorDatos.getVuelos();
        for (int i = 0; i < vuelos.size() && i < grafo.aristas.size(); i++) {
            Integer cap = vuelos.get(i).getCapacidad();
            if (cap != null && grafo.aristas.get(i).capacidad != cap) {
                grafo.aristas.get(i).capacidad = cap;
            }
        }
    }

    public void restaurarCapacidadesAFabrica() {
        int aeropuertosBd = jdbcTemplate.update(
                "UPDATE aeropuerto SET capacidad_almacen = capacidad_almacen_original " +
                "WHERE capacidad_almacen_original IS NOT NULL AND capacidad_almacen <> capacidad_almacen_original");
        int vuelosBd = jdbcTemplate.update(
                "UPDATE vuelo SET capacidad_maxima = capacidad_maxima_original " +
                "WHERE capacidad_maxima_original IS NOT NULL AND capacidad_maxima <> capacidad_maxima_original");

        Grafo grafo = motorCache.grafoSiExiste();

        for (Aeropuerto a : cargadorDatos.getAeropuertos()) {
            Integer orig = a.getCapacidadOriginal();
            if (orig == null) continue;
            a.setCapacidad(orig);
            if (grafo != null) {
                Nodo nodo = grafo.nodos.get(a.getCodigo());
                if (nodo != null) {
                    nodo.capacidad = orig;
                    nodo.capacidadAlmacen = orig;
                }
            }
        }
        List<Vuelo> vuelos = cargadorDatos.getVuelos();
        for (int i = 0; i < vuelos.size(); i++) {
            Integer orig = vuelos.get(i).getCapacidadOriginal();
            if (orig == null) continue;
            vuelos.get(i).setCapacidad(orig);
            if (grafo != null && i < grafo.aristas.size()) grafo.aristas.get(i).capacidad = orig;
        }
        log.info("Capacidades restauradas a fábrica (BD: {} aeropuertos, {} vuelos; + RAM + grafo).",
                aeropuertosBd, vuelosBd);
    }

    public String actualizarHorarioVuelo(String idVuelo, String salida, String llegada) {
        boolean haySalida  = salida  != null && !salida.isBlank();
        boolean hayLlegada = llegada != null && !llegada.isBlank();
        if (!haySalida && !hayLlegada)
            throw new ParametroInvalidoException(
                    "se requiere al menos un parámetro: salida y/o llegada (formato \"HH:mm\")");
        LocalTime hSalida  = haySalida  ? AltasEnCalienteService.parseHora(salida, "salida")   : null;
        LocalTime hLlegada = hayLlegada ? AltasEnCalienteService.parseHora(llegada, "llegada") : null;
        if (registroJobs.haySimulacionEnCurso())
            throw new IllegalStateException("Hay una simulación en curso; el horario de un vuelo solo se "
                    + "modifica EN FRÍO. Equivalente en caliente: cancelar el vuelo-día y agregar-vuelo "
                    + "con el horario nuevo.");

        String idDb = idVuelo.replace(":", "");
        int i = indiceVuelo(idDb);
        if (i < 0) return null;
        Vuelo v = cargadorDatos.getVuelos().get(i);
        LocalTime salidaEff  = hSalida  != null ? hSalida  : v.getFechaHoraSalida().toLocalTime();
        LocalTime llegadaEff = hLlegada != null ? hLlegada : v.getFechaHoraLlegada().toLocalTime();
        String nuevoId = v.getOrigen() + "-" + v.getDestino() + "-"
                + String.format("%02d%02d", salidaEff.getHour(), salidaEff.getMinute());
        boolean renombra = !nuevoId.equals(idDb);
        if (renombra && indiceVuelo(nuevoId) >= 0)
            throw new ParametroInvalidoException("ya existe un vuelo con id " + nuevoId
                    + "; elija otra hora de salida o modifique ese vuelo");

        if (renombra) borrarReferenciasSolucion(idDb);
        int filas = jdbcTemplate.update(
                "UPDATE vuelo SET id_vuelo = ?, hora_salida = ?, hora_llegada = ? WHERE id_vuelo = ?",
                nuevoId, hhmm(salidaEff), hhmm(llegadaEff), idDb);
        if (filas == 0) return null;

        v.setIdVuelo(nuevoId);
        aplicarHorarioEnRam(v, salidaEff, llegadaEff);
        motorCache.invalidar();   // el grafo del run se reconstruye desde RAM al iniciar el siguiente job
        log.info("Horario de vuelo {} → {} (salida {} / llegada {} local) — EN FRÍO, BD+RAM; grafo y "
                + "esqueletos invalidados.", idDb, nuevoId, hhmm(salidaEff), hhmm(llegadaEff));
        return nuevoId;
    }

    public String actualizarDestinoVuelo(String idVuelo, String destino, String llegada) {
        if (destino == null || destino.isBlank())
            throw new ParametroInvalidoException(
                    "se requiere el parámetro valor con el ICAO del nuevo destino (4 letras)");
        String icaoDestino = destino.trim().toUpperCase();
        LocalTime hLlegada = (llegada != null && !llegada.isBlank())
                ? AltasEnCalienteService.parseHora(llegada, "llegada") : null;
        if (registroJobs.haySimulacionEnCurso())
            throw new IllegalStateException("Hay una simulación en curso; el destino de un vuelo solo se "
                    + "modifica EN FRÍO. Equivalente en caliente: cancelar el vuelo-día y agregar-vuelo "
                    + "hacia el destino nuevo.");

        String idDb = idVuelo.replace(":", "");
        int i = indiceVuelo(idDb);
        if (i < 0) return null;
        Vuelo v = cargadorDatos.getVuelos().get(i);
        if (icaoDestino.equals(v.getOrigen()))
            throw new ParametroInvalidoException("origen y destino no pueden ser iguales");
        Aeropuerto aeropuertoDestino = cargadorDatos.getAeropuerto(icaoDestino);
        if (aeropuertoDestino == null)
            throw new ParametroInvalidoException("ICAO destino desconocido: " + icaoDestino);

        LocalTime salida = v.getFechaHoraSalida().toLocalTime();
        LocalTime llegadaEff = hLlegada != null ? hLlegada : v.getFechaHoraLlegada().toLocalTime();
        String nuevoId = v.getOrigen() + "-" + icaoDestino + "-"
                + String.format("%02d%02d", salida.getHour(), salida.getMinute());
        boolean renombra = !nuevoId.equals(idDb);
        if (renombra && indiceVuelo(nuevoId) >= 0)
            throw new ParametroInvalidoException("ya existe un vuelo con id " + nuevoId
                    + "; modifique ese vuelo o elija otro destino");

        if (renombra) borrarReferenciasSolucion(idDb);
        int filas = jdbcTemplate.update(
                "UPDATE vuelo SET id_vuelo = ?, icao_destino = ?, hora_llegada = ? WHERE id_vuelo = ?",
                nuevoId, icaoDestino, hhmm(llegadaEff), idDb);
        if (filas == 0) return null;

        v.setIdVuelo(nuevoId);
        v.setDestino(icaoDestino);
        v.setAeropuertoDestino(aeropuertoDestino);
        aplicarHorarioEnRam(v, salida, llegadaEff);
        motorCache.invalidar();
        log.info("Destino de vuelo {} → {} (llegada {} local) — EN FRÍO, BD+RAM; grafo y esqueletos "
                + "invalidados.", idDb, nuevoId, hhmm(llegadaEff));
        return nuevoId;
    }

    public int restaurarHorariosVuelosAFabrica() {
        if (registroJobs.haySimulacionEnCurso())
            throw new IllegalStateException(
                    "Hay una simulación en curso; los horarios solo se restauran EN FRÍO.");
        List<Map<String, Object>> modificados = jdbcTemplate.queryForList(
                "SELECT id_vuelo, icao_origen, icao_destino, icao_destino_original, "
              + "hora_salida_original, hora_llegada_original "
              + "FROM vuelo "
              + "WHERE hora_salida_original IS NOT NULL AND hora_llegada_original IS NOT NULL "
              + "AND (hora_salida IS DISTINCT FROM hora_salida_original "
              + "  OR hora_llegada IS DISTINCT FROM hora_llegada_original "
              + "  OR (icao_destino_original IS NOT NULL "
              + "      AND icao_destino IS DISTINCT FROM icao_destino_original))");
        if (modificados.isEmpty()) {
            log.info("Restaurar horarios de vuelo: ningún vuelo modificado (no-op).");
            return 0;
        }
        Map<String, Vuelo> porId = new HashMap<>();
        for (Vuelo v : cargadorDatos.getVuelos()) porId.put(v.getIdVuelo(), v);
        int restaurados = 0;
        for (Map<String, Object> fila : modificados) {
            String idActual = (String) fila.get("id_vuelo");
            try {
                LocalTime salidaOrig = AltasEnCalienteService.parseHora(
                        (String) fila.get("hora_salida_original"), "hora_salida_original");
                LocalTime llegadaOrig = AltasEnCalienteService.parseHora(
                        (String) fila.get("hora_llegada_original"), "hora_llegada_original");
                // BD anterior al backfill de icao_destino_original: conservar el destino vigente.
                String destinoOrig = fila.get("icao_destino_original") != null
                        ? (String) fila.get("icao_destino_original")
                        : (String) fila.get("icao_destino");
                String idOriginal = fila.get("icao_origen") + "-" + destinoOrig + "-"
                        + String.format("%02d%02d", salidaOrig.getHour(), salidaOrig.getMinute());
                if (!idOriginal.equals(idActual)) borrarReferenciasSolucion(idActual);
                jdbcTemplate.update(
                        "UPDATE vuelo SET id_vuelo = ?, icao_destino = ?, hora_salida = ?, "
                      + "hora_llegada = ? WHERE id_vuelo = ?",
                        idOriginal, destinoOrig, hhmm(salidaOrig), hhmm(llegadaOrig), idActual);
                Vuelo v = porId.get(idActual);
                if (v != null) {
                    v.setIdVuelo(idOriginal);
                    v.setDestino(destinoOrig);
                    Aeropuerto aOrig = cargadorDatos.getAeropuerto(destinoOrig);
                    if (aOrig != null) v.setAeropuertoDestino(aOrig);
                    aplicarHorarioEnRam(v, salidaOrig, llegadaOrig);
                }
                restaurados++;
            } catch (Exception ex) {
                log.warn("No se pudo restaurar el horario del vuelo {}: {}", idActual, ex.getMessage());
            }
        }
        motorCache.invalidar();
        log.info("Horarios de vuelo restaurados a fábrica: {} vuelo(s) (BD+RAM; grafo invalidado).",
                restaurados);
        return restaurados;
    }

    private void borrarReferenciasSolucion(String idVuelo) {
        jdbcTemplate.update("DELETE FROM tramo_ruta        WHERE id_vuelo = ?", idVuelo);
        jdbcTemplate.update("DELETE FROM tramo_inyectado   WHERE id_vuelo = ?", idVuelo);
        jdbcTemplate.update("DELETE FROM cancelacion_vuelo WHERE id_vuelo = ?", idVuelo);
    }

    private static void aplicarHorarioEnRam(Vuelo v, LocalTime salida, LocalTime llegada) {
        LocalDateTime fechaSalida = LocalDateTime.of(AnalizadorVuelos.FLIGHT_BASE_DATE, salida);
        v.setFechaHoraSalida(fechaSalida);
        v.setFechaHoraLlegada(CargadorDatos.fechaLlegadaLocal(fechaSalida, llegada,
                v.getAeropuertoOrigen(), v.getAeropuertoDestino()));
    }

    private static String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private int indiceVuelo(String idDb) {
        List<Vuelo> vuelos = cargadorDatos.getVuelos();
        for (int i = 0; i < vuelos.size(); i++) {
            if (idDb.equals(vuelos.get(i).getIdVuelo())) return i;
        }
        return -1;
    }

    private static void validarValor(int valor) {
        if (valor < 1) {
            throw new ParametroInvalidoException("La capacidad debe ser un entero >= 1 (recibido: " + valor + ").");
        }
    }
}
