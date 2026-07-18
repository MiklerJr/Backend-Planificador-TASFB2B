package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.dto.datos.AltaAeropuertoRequest;
import com.tasfb2b.planificador.dto.vuelos.AltaVueloRequest;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.MapeadorAlgoritmo;
import com.tasfb2b.planificador.utilidades.analizador.AnalizadorVuelos;
import com.tasfb2b.planificador.utilidades.validador.ValidadorEnvio;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class AltasEnCalienteService {

    private final JdbcTemplate jdbcTemplate;
    private final CargadorDatos cargadorDatos;
    private final MotorGrafoCache motorCache;

    private static final DateTimeFormatter FORMATO_HORA_ENTRADA = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter FORMATO_HORA_BD      = DateTimeFormatter.ofPattern("HH:mm");

    private static final Set<String> CONTINENTES_VALIDOS = Set.of("AM", "EU", "AS");

    private final List<String> vuelosEfimerosAplicados = new CopyOnWriteArrayList<>();

    private final List<String> aeropuertosEfimerosAplicados = new CopyOnWriteArrayList<>();

    public AltasEnCalienteService(JdbcTemplate jdbcTemplate,
                                  CargadorDatos cargadorDatos,
                                  MotorGrafoCache motorCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.cargadorDatos = cargadorDatos;
        this.motorCache = motorCache;
    }

    public boolean hayAltasActivas() {
        return !vuelosEfimerosAplicados.isEmpty() || !aeropuertosEfimerosAplicados.isEmpty();
    }

    public void validarAltaVuelo(AltaVueloRequest req) {
        validarAltaVuelo(req, Collections.emptySet());
    }

    public void validarAltaVuelo(AltaVueloRequest req, Set<String> icaosPendientes) {
        if (req == null) throw new ParametroInvalidoException("alta de vuelo vacía");
        if (!ValidadorEnvio.camposObligatoriosPresentes(req.getOrigen(), req.getDestino()))
            throw new ParametroInvalidoException("origen y destino son obligatorios");
        if (ValidadorEnvio.esMismoAeropuerto(req.getOrigen(), req.getDestino()))
            throw new ParametroInvalidoException("origen y destino no pueden ser iguales");
        String origen = req.getOrigen().trim();
        String destino = req.getDestino().trim();
        if (cargadorDatos.getAeropuerto(origen) == null && !icaosPendientes.contains(origen))
            throw new ParametroInvalidoException("ICAO origen desconocido: " + req.getOrigen());
        if (cargadorDatos.getAeropuerto(destino) == null && !icaosPendientes.contains(destino))
            throw new ParametroInvalidoException("ICAO destino desconocido: " + req.getDestino());
        parseHora(req.getHoraSalida(), "horaSalida");
        parseHora(req.getHoraLlegada(), "horaLlegada");
        if (req.getCapacidad() < 1)
            throw new ParametroInvalidoException(
                    "la capacidad debe ser un entero >= 1 (recibido: " + req.getCapacidad() + ")");
        String idVuelo = idVueloDe(req);
        if (existeIdVuelo(idVuelo))
            throw new ParametroInvalidoException("ya existe un vuelo con id " + idVuelo);
    }

    public void validarAltaAeropuerto(AltaAeropuertoRequest req) {
        if (req == null) throw new ParametroInvalidoException("alta de aeropuerto vacía");
        String icao = req.getIcao() == null ? "" : req.getIcao().trim();
        if (!icao.matches("[A-Z]{4}"))
            throw new ParametroInvalidoException(
                    "ICAO inválido: '" + req.getIcao() + "' (se esperan 4 letras mayúsculas)");
        if (cargadorDatos.getAeropuerto(icao) != null)
            throw new ParametroInvalidoException("ya existe un aeropuerto con ICAO " + icao);
        if (req.getHusoHorario() == null || req.getHusoHorario() < -12 || req.getHusoHorario() > 14)
            throw new ParametroInvalidoException(
                    "husoHorario es obligatorio y debe estar en [-12..14] (recibido: " + req.getHusoHorario() + ")");
        if (req.getCapacidad() < 1)
            throw new ParametroInvalidoException(
                    "la capacidad debe ser un entero >= 1 (recibido: " + req.getCapacidad() + ")");
        continenteDe(req);
    }

    static String continenteDe(AltaAeropuertoRequest req) {
        String explicito = req.getContinente() == null ? "" : req.getContinente().trim().toUpperCase();
        if (!explicito.isEmpty()) {
            if (!CONTINENTES_VALIDOS.contains(explicito))
                throw new ParametroInvalidoException(
                        "continente inválido: '" + req.getContinente() + "' (valores: AM, EU, AS)");
            return explicito;
        }
        String derivado = CargadorDatos.continentePorIcao(req.getIcao() == null ? "" : req.getIcao().trim());
        if (!CONTINENTES_VALIDOS.contains(derivado))
            throw new ParametroInvalidoException(
                    "el prefijo ICAO no permite derivar el continente; envíe 'continente' (AM, EU o AS)");
        return derivado;
    }

    public synchronized String aplicarAltaVuelo(AltaVueloRequest req, Grafo grafo,
                                                OperadorReparacionVoraz enrutador) {
        try {
            validarAltaVuelo(req);
        } catch (ParametroInvalidoException ex) {
            return ex.getMessage();
        }
        if (grafo == null || enrutador == null) return "grafo/enrutador no disponibles";

        Aeropuerto origen  = cargadorDatos.getAeropuerto(req.getOrigen().trim());
        Aeropuerto destino = cargadorDatos.getAeropuerto(req.getDestino().trim());
        LocalTime hSalida  = parseHora(req.getHoraSalida(), "horaSalida");
        LocalTime hLlegada = parseHora(req.getHoraLlegada(), "horaLlegada");
        String idVuelo = idVueloDe(req);

        if (jdbcTemplate != null) {
            try {
                int filas = jdbcTemplate.update(
                        "INSERT INTO vuelo (id_vuelo, icao_origen, icao_destino, hora_salida, hora_llegada, "
                      + "capacidad_maxima, capacidad_maxima_original, efimero) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, TRUE) ON CONFLICT DO NOTHING",
                        idVuelo, origen.getCodigo(), destino.getCodigo(),
                        hSalida.format(FORMATO_HORA_BD), hLlegada.format(FORMATO_HORA_BD),
                        req.getCapacidad(), req.getCapacidad());
                if (filas == 0) return "el id " + idVuelo + " ya existe en la BD";
            } catch (Exception ex) {
                return "INSERT del vuelo en BD falló: " + ex.getMessage();
            }
        }

        Vuelo v = new Vuelo();
        v.setIdVuelo(idVuelo);
        v.setOrigen(origen.getCodigo());
        v.setDestino(destino.getCodigo());
        v.setCapacidad(req.getCapacidad());
        v.setCapacidadOriginal(req.getCapacidad());
        v.setEfimero(true);
        LocalDateTime fechaSalida = LocalDateTime.of(AnalizadorVuelos.FLIGHT_BASE_DATE, hSalida);
        v.setFechaHoraSalida(fechaSalida);
        v.setFechaHoraLlegada(CargadorDatos.fechaLlegadaLocal(fechaSalida, hLlegada, origen, destino));
        v.setAeropuertoOrigen(origen);
        v.setAeropuertoDestino(destino);

        int indice = grafo.aristas.size();
        try {
            cargadorDatos.agregarVueloEfimero(v);
            Arista e = MapeadorAlgoritmo.construirArista(v, grafo, indice);
            grafo.agregarArista(e);
            if (!enrutador.incorporarArista(e)) {
                throw new IllegalStateException("incorporarArista rechazó la arista (índice " + indice + ")");
            }
            motorCache.cacheEsqueletos().clear();
            vuelosEfimerosAplicados.add(idVuelo);
            log.info("Alta EN CALIENTE aplicada: vuelo {} ({}→{}, {}–{} local, cap {}), arista idx {} — efímero por corrida.",
                    idVuelo, origen.getCodigo(), destino.getCodigo(),
                    req.getHoraSalida(), req.getHoraLlegada(), req.getCapacidad(), indice);
            return null;
        } catch (Exception ex) {
            cargadorDatos.getVuelos().remove(v);
            grafo.recortarAristasDesde(indice);
            if (jdbcTemplate != null) {
                try { jdbcTemplate.update("DELETE FROM vuelo WHERE id_vuelo = ?", idVuelo); }
                catch (Exception ignored) { /* residuo cubierto por la limpieza de arranque */ }
            }
            log.warn("Alta EN CALIENTE de vuelo {} falló y fue compensada: {}", idVuelo, ex.getMessage());
            return "aplicación en memoria falló: " + ex.getMessage();
        }
    }

    public synchronized String aplicarAltaAeropuerto(AltaAeropuertoRequest req, Grafo grafo,
                                                     OperadorReparacionVoraz enrutador) {
        try {
            validarAltaAeropuerto(req);
        } catch (ParametroInvalidoException ex) {
            return ex.getMessage();
        }
        if (grafo == null || enrutador == null) return "grafo/enrutador no disponibles";

        String icao = req.getIcao().trim();
        String continente = continenteDe(req);

        if (jdbcTemplate != null) {
            try {
                int filas = jdbcTemplate.update(
                        "INSERT INTO aeropuerto (icao, ciudad, huso_horario, capacidad_almacen, "
                      + "capacidad_almacen_original, latitud, longitud, activo, efimero) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, TRUE) ON CONFLICT (icao) DO NOTHING",
                        icao, req.getCiudad(), req.getHusoHorario(), req.getCapacidad(),
                        req.getCapacidad(), req.getLatitud(), req.getLongitud());
                if (filas == 0) return "el ICAO " + icao + " ya existe en la BD";
            } catch (Exception ex) {
                return "INSERT del aeropuerto en BD falló: " + ex.getMessage();
            }
        }

        Aeropuerto a = new Aeropuerto();
        a.setCodigo(icao);
        a.setCiudad(req.getCiudad());
        a.setOffsetHorario(req.getHusoHorario());
        a.setCapacidad(req.getCapacidad());
        a.setCapacidadOriginal(req.getCapacidad());
        a.setLatitud(req.getLatitud());
        a.setLongitud(req.getLongitud());
        a.setContinente(continente);
        a.setActivo(true);
        a.setEfimero(true);

        try {
            cargadorDatos.agregarAeropuertoEfimero(a);
            Nodo nodo = grafo.agregarNodo(icao, req.getCapacidad());
            enrutador.incorporarNodo(nodo);
            aeropuertosEfimerosAplicados.add(icao);
            log.info("Alta EN CALIENTE aplicada: aeropuerto {} (huso {}, cap {}, continente {}), nodo idx {} — efímero por corrida.",
                    icao, req.getHusoHorario(), req.getCapacidad(), continente, nodo.indice);
            return null;
        } catch (Exception ex) {
            cargadorDatos.quitarAeropuertoEfimero(a);
            grafo.eliminarNodo(icao);
            if (jdbcTemplate != null) {
                try { jdbcTemplate.update("DELETE FROM aeropuerto WHERE icao = ?", icao); }
                catch (Exception ignored) { /* residuo cubierto por la limpieza de arranque */ }
            }
            log.warn("Alta EN CALIENTE de aeropuerto {} falló y fue compensada: {}", icao, ex.getMessage());
            return "aplicación en memoria falló: " + ex.getMessage();
        }
    }

    public synchronized String aplicarAltaVueloEnFrio(AltaVueloRequest req) {
        try {
            validarAltaVuelo(req);
        } catch (ParametroInvalidoException ex) {
            return ex.getMessage();
        }

        Aeropuerto origen  = cargadorDatos.getAeropuerto(req.getOrigen().trim());
        Aeropuerto destino = cargadorDatos.getAeropuerto(req.getDestino().trim());
        LocalTime hSalida  = parseHora(req.getHoraSalida(), "horaSalida");
        LocalTime hLlegada = parseHora(req.getHoraLlegada(), "horaLlegada");
        String idVuelo = idVueloDe(req);

        if (jdbcTemplate != null) {
            try {
                int filas = jdbcTemplate.update(
                        "INSERT INTO vuelo (id_vuelo, icao_origen, icao_destino, hora_salida, hora_llegada, "
                      + "capacidad_maxima, capacidad_maxima_original, efimero) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, TRUE) ON CONFLICT DO NOTHING",
                        idVuelo, origen.getCodigo(), destino.getCodigo(),
                        hSalida.format(FORMATO_HORA_BD), hLlegada.format(FORMATO_HORA_BD),
                        req.getCapacidad(), req.getCapacidad());
                if (filas == 0) return "el id " + idVuelo + " ya existe en la BD";
            } catch (Exception ex) {
                return "INSERT del vuelo en BD falló: " + ex.getMessage();
            }
        }

        Vuelo v = new Vuelo();
        v.setIdVuelo(idVuelo);
        v.setOrigen(origen.getCodigo());
        v.setDestino(destino.getCodigo());
        v.setCapacidad(req.getCapacidad());
        v.setCapacidadOriginal(req.getCapacidad());
        v.setEfimero(true);
        LocalDateTime fechaSalida = LocalDateTime.of(AnalizadorVuelos.FLIGHT_BASE_DATE, hSalida);
        v.setFechaHoraSalida(fechaSalida);
        v.setFechaHoraLlegada(CargadorDatos.fechaLlegadaLocal(fechaSalida, hLlegada, origen, destino));
        v.setAeropuertoOrigen(origen);
        v.setAeropuertoDestino(destino);

        cargadorDatos.agregarVueloEfimero(v);
        vuelosEfimerosAplicados.add(idVuelo);
        log.info("Alta EN FRÍO aplicada: vuelo {} ({}→{}, {}–{} local, cap {}) — entra al grafo en la próxima corrida.",
                idVuelo, origen.getCodigo(), destino.getCodigo(),
                req.getHoraSalida(), req.getHoraLlegada(), req.getCapacidad());
        return null;
    }

    public synchronized int eliminarVuelosEfimeros() {
        int enBd = 0;
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update("DELETE FROM tramo_ruta "
                        + "WHERE id_vuelo IN (SELECT id_vuelo FROM vuelo WHERE efimero)");
                jdbcTemplate.update("DELETE FROM tramo_inyectado "
                        + "WHERE id_vuelo IN (SELECT id_vuelo FROM vuelo WHERE efimero)");
                jdbcTemplate.update("DELETE FROM cancelacion_vuelo "
                        + "WHERE id_vuelo IN (SELECT id_vuelo FROM vuelo WHERE efimero)");
                enBd = jdbcTemplate.update("DELETE FROM vuelo WHERE efimero");
            } catch (Exception ex) {
                log.warn("Eliminación BD de vuelos efímeros falló: {}", ex.getMessage());
            }
        }
        int enRam = 0;
        for (Vuelo v : cargadorDatos.getVuelos()) if (v.isEfimero()) enRam++;
        if (enBd == 0 && enRam == 0) return 0;

        cargadorDatos.quitarVuelosEfimeros();
        vuelosEfimerosAplicados.clear();
        motorCache.invalidar();
        log.info("Vuelos agregados eliminados: {} en BD / {} en RAM (grafo y esqueletos invalidados).",
                enBd, enRam);
        return Math.max(enBd, enRam);
    }

    public synchronized void revertirAltasEfimeras() {
        boolean habiaRegistro = hayAltasActivas();
        int enBd = 0;
        if (jdbcTemplate != null) {
            try {
                Integer n = jdbcTemplate.queryForObject(
                        "SELECT (SELECT count(*) FROM vuelo WHERE efimero) "
                      + "     + (SELECT count(*) FROM aeropuerto WHERE efimero)", Integer.class);
                enBd = n != null ? n : 0;
            } catch (Exception ex) {
                log.warn("No se pudo consultar altas efímeras en BD: {}", ex.getMessage());
            }
        }
        if (!habiaRegistro && enBd == 0) return;

        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update("DELETE FROM envio_inyectado "
                        + "WHERE icao_origen  IN (SELECT icao FROM aeropuerto WHERE efimero) "
                        + "   OR icao_destino IN (SELECT icao FROM aeropuerto WHERE efimero)");
                jdbcTemplate.update("DELETE FROM vuelo WHERE efimero");
                jdbcTemplate.update("DELETE FROM aeropuerto WHERE efimero");
            } catch (Exception ex) {
                log.warn("Reversión BD de altas efímeras falló (la limpieza de arranque de schema.sql "
                        + "cubre el residuo): {}", ex.getMessage());
            }
        }

        int baseVuelos = 0;
        for (Vuelo v : cargadorDatos.getVuelos()) if (!v.isEfimero()) baseVuelos++;
        int baseNodos = 0;
        List<String> icaosEfimeros = new ArrayList<>();
        for (Aeropuerto a : cargadorDatos.getAeropuertos()) {
            if (a.isEfimero()) icaosEfimeros.add(a.getCodigo());
            else baseNodos++;
        }

        Grafo grafo = motorCache.grafoSiExiste();
        if (grafo != null) {
            grafo.recortarAristasDesde(baseVuelos);
            for (String icao : icaosEfimeros) grafo.eliminarNodo(icao);
        }

        cargadorDatos.quitarVuelosEfimeros();
        cargadorDatos.quitarAeropuertosEfimeros();

        purgarEsqueletosConIndiceDesde(baseVuelos);
        if (!icaosEfimeros.isEmpty()) purgarClavesConNodoDesde(baseNodos);

        log.info("Altas EN CALIENTE revertidas: {} vuelo(s) y {} aeropuerto(s) del registro / {} fila(s) efímera(s) en BD.",
                vuelosEfimerosAplicados.size(), aeropuertosEfimerosAplicados.size(), enBd);
        vuelosEfimerosAplicados.clear();
        aeropuertosEfimerosAplicados.clear();
    }

    private void purgarClavesConNodoDesde(int nodoBase) {
        motorCache.cacheEsqueletos().keySet().removeIf(key ->
                (int) (key >>> 40) >= nodoBase || (int) ((key >> 24) & 0xFFFF) >= nodoBase);
    }

    private void purgarEsqueletosConIndiceDesde(int indiceBase) {
        Map<Long, List<int[]>> cache = motorCache.cacheEsqueletos();
        for (Map.Entry<Long, List<int[]>> entry : cache.entrySet()) {
            boolean contaminada = false;
            for (int[] sk : entry.getValue()) {
                if (contieneIndiceDesde(sk, indiceBase)) { contaminada = true; break; }
            }
            if (!contaminada) continue;
            List<int[]> filtrados = new ArrayList<>();
            for (int[] sk : entry.getValue()) {
                if (!contieneIndiceDesde(sk, indiceBase)) filtrados.add(sk);
            }
            if (filtrados.isEmpty()) cache.remove(entry.getKey());
            else cache.put(entry.getKey(), filtrados);
        }
    }

    static boolean contieneIndiceDesde(int[] esqueleto, int indiceBase) {
        for (int idx : esqueleto) if (idx >= indiceBase) return true;
        return false;
    }

    public static String idVueloDe(AltaVueloRequest req) {
        LocalTime hs = parseHora(req.getHoraSalida(), "horaSalida");
        return req.getOrigen().trim() + "-" + req.getDestino().trim() + "-"
                + String.format("%02d%02d", hs.getHour(), hs.getMinute());
    }

    static LocalTime parseHora(String valor, String campo) {
        if (valor == null || valor.isBlank())
            throw new ParametroInvalidoException(campo + " es obligatoria (formato \"HH:mm\")");
        try {
            return LocalTime.parse(valor.trim(), FORMATO_HORA_ENTRADA);
        } catch (DateTimeParseException ex) {
            throw new ParametroInvalidoException(
                    campo + " inválida: '" + valor + "' (formato esperado \"HH:mm\")");
        }
    }

    public record LineaVueloTxt(int numeroLinea, String contenido,
                                AltaVueloRequest alta, String motivoDescarte) { }

    public static List<LineaVueloTxt> parsearAltasVueloTxt(Reader reader) throws IOException {
        BufferedReader br = (reader instanceof BufferedReader b) ? b : new BufferedReader(reader);
        List<LineaVueloTxt> lineas = new ArrayList<>();
        String linea;
        int n = 0;
        while ((linea = br.readLine()) != null) {
            n++;
            String s = linea.replace(String.valueOf((char) 0xFEFF), "").trim();
            if (s.isEmpty() || s.startsWith("*") || s.startsWith("//") || s.contains("ORIG-DEST"))
                continue;
            String[] parts = s.split("[\\s-]+");
            if (parts.length < 5) {
                lineas.add(new LineaVueloTxt(n, s, null,
                        "formato inválido (se esperan 5 campos ORIG-DEST-HH:MM-HH:MM-CAPACIDAD)"));
                continue;
            }
            if (!parts[4].matches("\\d+")) {
                lineas.add(new LineaVueloTxt(n, s, null, "capacidad no numérica: '" + parts[4] + "'"));
                continue;
            }
            AltaVueloRequest alta = new AltaVueloRequest();
            alta.setOrigen(parts[0].trim());
            alta.setDestino(parts[1].trim());
            alta.setHoraSalida(parts[2].trim());
            alta.setHoraLlegada(parts[3].trim());
            alta.setCapacidad(Integer.parseInt(parts[4]));
            lineas.add(new LineaVueloTxt(n, s, alta, null));
        }
        return lineas;
    }

    private boolean existeIdVuelo(String idVuelo) {
        for (Vuelo v : cargadorDatos.getVuelos()) {
            if (idVuelo.equals(v.getIdVuelo())) return true;
        }
        return false;
    }
}
