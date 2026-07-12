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

/**
 * Modificación de la capacidad de aeropuertos y vuelos con TRES niveles de valor y dos modos de edición.
 *
 * <p>Niveles (solo dos columnas en BD; el nivel "caliente" vive en memoria):
 * <ul>
 *   <li><b>Original de fábrica</b> — {@code capacidad_*_original} (BD, intocable). Destino del botón restaurar.</li>
 *   <li><b>Baseline en frío</b> — {@code capacidad_*} (BD) + RAM de {@link CargadorDatos}. Persiste entre corridas.</li>
 *   <li><b>Override en caliente</b> — solo el grafo cacheado del run en curso ({@link MotorGrafoCache}). Efímero.</li>
 * </ul>
 *
 * <p>Modo automático por estado ({@link RegistroJobs#haySimulacionEnCurso()}):
 * <ul>
 *   <li><b>Sin simulación en curso ⇒ EN FRÍO</b>: escribe BD + RAM + grafo. Persiste; el próximo job arranca con ese valor.</li>
 *   <li><b>Con simulación en curso ⇒ EN CALIENTE</b>: escribe solo el grafo del run. Efecto inmediato pero se descarta
 *       al iniciar el siguiente job ({@link #resincronizarCapacidadesConBaselineFrio()} lo repone desde RAM).</li>
 * </ul>
 * El botón restaurar ({@link #restaurarCapacidadesAFabrica()}) devuelve TODO (BD + RAM + grafo) al original de fábrica.
 */
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

    /**
     * @return true si el aeropuerto existe (cambio aplicado), false si no (→ 404 en el controlador).
     * @throws ParametroInvalidoException si {@code valor < 1} (→ 400).
     */
    public boolean actualizarCapacidadAeropuerto(String icao, int valor) {
        validarValor(valor);
        Grafo grafo = motorCache.grafoSiExiste();

        if (registroJobs.haySimulacionEnCurso()) {
            // EN CALIENTE: solo el grafo del run en curso.
            Nodo nodo = grafo != null ? grafo.nodos.get(icao) : null;
            if (nodo == null) return false;
            nodo.capacidad = valor;
            nodo.capacidadAlmacen = valor;
            log.info("Capacidad de almacén {} → {} (EN CALIENTE, solo grafo del run).", icao, valor);
            return true;
        }

        // EN FRÍO: BD + RAM + grafo.
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

    /**
     * @param idVuelo id del frontend, con o sin los dos puntos de la hora ("SKBO-SEQM-08:30" o "...-0830").
     * @return true si el vuelo existe, false si no (→ 404).
     * @throws ParametroInvalidoException si {@code valor < 1} (→ 400).
     */
    public boolean actualizarCapacidadVuelo(String idVuelo, int valor) {
        validarValor(valor);
        String idDb = idVuelo.replace(":", "");
        Grafo grafo = motorCache.grafoSiExiste();

        if (registroJobs.haySimulacionEnCurso()) {
            // EN CALIENTE: solo el grafo del run en curso.
            int i = indiceVuelo(idDb);
            if (i < 0) return false;
            if (grafo != null && i < grafo.aristas.size()) grafo.aristas.get(i).capacidad = valor;
            log.info("Capacidad de vuelo {} → {} (EN CALIENTE, solo grafo del run).", idDb, valor);
            return true;
        }

        // EN FRÍO: BD + RAM + grafo.
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

    /**
     * Repone en el grafo cacheado el baseline EN FRÍO (las capacidades de RAM), descartando los overrides
     * EN CALIENTE del run anterior y conservando lo configurado en frío. Se invoca al inicio de cada corrida.
     * No toca la BD (el baseline en frío ya está persistido).
     */
    public void resincronizarCapacidadesConBaselineFrio() {
        Grafo grafo = motorCache.grafoSiExiste();
        if (grafo == null) return;   // primer job: el grafo se construirá fresco desde RAM (baseline en frío)

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

    /**
     * Botón restaurar: devuelve TODAS las capacidades (aeropuertos y vuelos) al valor original de fábrica en
     * BD + RAM + grafo. Aplica en vivo (si hay un job en curso, su grafo pasa a los originales). Idempotente.
     */
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

    /**
     * Modifica el horario (hora_salida/hora_llegada LOCALES "HH:mm") de un vuelo existente — y con ello
     * su duración, que deriva {@code MapeadorAlgoritmo} al reconstruir el grafo. <b>Solo EN FRÍO</b>:
     * con una simulación en curso lanza {@link IllegalStateException} (→ 409 en el controlador) porque
     * el horario cambia la topología temporal del grafo y la ocupación vuelo-día ya confirmada del run;
     * el equivalente seguro en caliente ya existe (cancelar el vuelo-día + agregar-vuelo).
     *
     * <p><b>El id se renombra si cambia la salida</b>: el invariante del sistema es
     * {@code id_vuelo ≡ ORIGEN-DESTINO-HHMM(salida actual)} — la persistencia de tramos y cancelaciones
     * deriva el id de la hora vigente de la arista ({@code Arista.id} → {@code normalizarIdVuelo}), así
     * que conservar el id viejo rompería la FK {@code tramo_ruta.id_vuelo → vuelo} en la corrida
     * siguiente. Como la PK no tiene ON UPDATE CASCADE, antes de renombrar se despejan las FKs borrando
     * los tramos/cancelaciones de la corrida ANTERIOR que referencien el vuelo (la corrida siguiente
     * los truncaría de todos modos).
     *
     * <p>Invalida el grafo cacheado y los esqueletos en memoria; el archivo persistido de esqueletos se
     * descarta solo (su huella SHA-256 incluye las horas de cada vuelo) y, si luego se restaura a
     * fábrica, vuelve a ser válido.
     *
     * @param salida  nueva hora local de salida "HH:mm" (null/blank = conservar la actual)
     * @param llegada nueva hora local de llegada "HH:mm" (null/blank = conservar la actual)
     * @return el id vigente tras el cambio (renombrado si cambió la salida), o null si el vuelo no
     *         existe (→ 404).
     * @throws ParametroInvalidoException hora malformada, ambos parámetros ausentes o colisión del id
     *                                    nuevo con un vuelo existente (→ 400).
     * @throws IllegalStateException      simulación en curso (→ 409).
     */
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

    /**
     * Cambia el aeropuerto DESTINO de un vuelo existente (re-ruta del plan). <b>Solo EN FRÍO</b>, por
     * las mismas razones que {@link #actualizarHorarioVuelo}: con simulación en curso lanza
     * {@link IllegalStateException} (→ 409); el equivalente en caliente es cancelar el vuelo-día +
     * agregar-vuelo hacia el destino nuevo.
     *
     * <p><b>El id se renombra siempre que el destino cambie</b> (invariante
     * {@code id_vuelo ≡ ORIGEN-DESTINO-HHMM(salida)}), con el mismo despeje de FKs que el cambio de
     * horario. Como {@code hora_llegada} es hora LOCAL del destino, cambiar de destino cambia el huso
     * con que se interpreta: si el llamador no pasa {@code llegada}, se conserva la hora local vigente
     * (la duración UTC resultante la deriva el mapeador con el huso del destino nuevo).
     *
     * @param destino ICAO del nuevo aeropuerto destino (obligatorio, debe existir en el catálogo).
     * @param llegada nueva hora local de llegada "HH:mm" (null/blank = conservar la actual).
     * @return el id vigente tras el cambio, o null si el vuelo no existe (→ 404).
     * @throws ParametroInvalidoException destino ausente/desconocido/igual al origen, hora malformada
     *                                    o colisión del id nuevo (→ 400).
     * @throws IllegalStateException      simulación en curso (→ 409).
     */
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
        aplicarHorarioEnRam(v, salida, llegadaEff);   // rehace la llegada con el huso del destino nuevo
        motorCache.invalidar();
        log.info("Destino de vuelo {} → {} (llegada {} local) — EN FRÍO, BD+RAM; grafo y esqueletos "
                + "invalidados.", idDb, nuevoId, hhmm(llegadaEff));
        return nuevoId;
    }

    /**
     * Devuelve el plan de TODOS los vuelos modificados a su valor original de fábrica — horarios
     * ({@code hora_*_original}) y destino ({@code icao_destino_original}) — en BD + RAM, renombrando el
     * id de vuelta al original, e invalida el grafo cacheado. Solo EN FRÍO ({@link IllegalStateException}
     * → 409 con simulación en curso). Idempotente: si nada fue modificado, no invalida nada.
     *
     * @return número de vuelos restaurados.
     */
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

    /**
     * Despeja las FKs antes de renombrar la PK de un vuelo: borra los tramos/cancelaciones de la corrida
     * ANTERIOR (ya terminada) que lo referencien. La corrida siguiente los truncaría de todos modos; las
     * consultas post-corrida de ese vuelo pierden sus tramos — efecto documentado de modificar horarios.
     */
    private void borrarReferenciasSolucion(String idVuelo) {
        jdbcTemplate.update("DELETE FROM tramo_ruta        WHERE id_vuelo = ?", idVuelo);
        jdbcTemplate.update("DELETE FROM tramo_inyectado   WHERE id_vuelo = ?", idVuelo);
        jdbcTemplate.update("DELETE FROM cancelacion_vuelo WHERE id_vuelo = ?", idVuelo);
    }

    /** Reconstruye en RAM las fechas ancladas a FLIGHT_BASE_DATE, exactamente como CargadorDatos.load(). */
    private static void aplicarHorarioEnRam(Vuelo v, LocalTime salida, LocalTime llegada) {
        LocalDateTime fechaSalida = LocalDateTime.of(AnalizadorVuelos.FLIGHT_BASE_DATE, salida);
        v.setFechaHoraSalida(fechaSalida);
        v.setFechaHoraLlegada(CargadorDatos.fechaLlegadaLocal(fechaSalida, llegada,
                v.getAeropuertoOrigen(), v.getAeropuertoDestino()));
    }

    private static String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    /** Índice del vuelo en {@code getVuelos()} == índice de su arista en el grafo (mapeo 1:1 en orden). */
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
