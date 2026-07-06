package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

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
