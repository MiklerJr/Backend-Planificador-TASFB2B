package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.MapeadorAlgoritmo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica los dos modos de edición de {@link ConfiguracionCapacidadesService} y sus tres niveles:
 * <ul>
 *   <li>EN FRÍO (sin job en curso): actualiza BD + RAM + grafo (persiste).</li>
 *   <li>EN CALIENTE (con job en curso): actualiza solo el grafo del run (efímero, no toca BD ni RAM).</li>
 *   <li>Resincronización al iniciar corrida: repone el grafo desde RAM (baseline en frío), borra lo caliente.</li>
 *   <li>Restaurar a fábrica: BD + RAM + grafo vuelven al original.</li>
 * </ul>
 * Usa un {@link JdbcTemplate} falso (sin BD) y datos sintéticos inyectados por reflexión en {@code CargadorDatos}.
 */
class ConfiguracionCapacidadesServiceTest {

    /** JdbcTemplate falso: nº de filas configurable, cuenta llamadas a update y no toca ninguna BD. */
    static class FakeJdbc extends JdbcTemplate {
        int filasAfectadas = 1;
        int updateCalls = 0;
        @Override public int update(String sql) { updateCalls++; return filasAfectadas; }
        @Override public int update(String sql, Object... args) { updateCalls++; return filasAfectadas; }
    }

    private FakeJdbc jdbc;
    private CargadorDatos cargador;
    private MotorGrafoCache motorCache;
    private RegistroJobs registro;
    private Grafo grafo;
    private ConfiguracionCapacidadesService servicio;

    private Aeropuerto skbo;
    private Vuelo vuelo;

    @BeforeEach
    void setUp() throws Exception {
        skbo = aeropuerto("SKBO", -5, 1000);
        Aeropuerto seqm = aeropuerto("SEQM", -5, 800);
        vuelo = vuelo("SKBO-SEQM-0830", skbo, seqm, 300);

        List<Aeropuerto> aeropuertos = List.of(skbo, seqm);
        List<Vuelo> vuelos = List.of(vuelo);

        jdbc = new FakeJdbc();
        cargador = new CargadorDatos(jdbc);
        inyectar(cargador, "aeropuertos", aeropuertos);
        inyectar(cargador, "vuelos", vuelos);
        inyectar(cargador, "aeropuertoMapCache",
                aeropuertos.stream().collect(Collectors.toMap(Aeropuerto::getCodigo, a -> a)));

        grafo = new MapeadorAlgoritmo().mapearAGrafo(aeropuertos, vuelos);
        motorCache = new MotorGrafoCache();
        motorCache.obtenerGrafo(() -> grafo);   // materializa el grafo cacheado

        registro = new RegistroJobs();
        servicio = new ConfiguracionCapacidadesService(jdbc, cargador, motorCache, registro);
    }

    /** Marca una simulación como en curso (motor usando el grafo) → modo EN CALIENTE. */
    private void simularJobEnCurso() {
        EstadoJob job = registro.crear("2", 1);
        job.estado = "ejecutando";
        assertTrue(registro.haySimulacionEnCurso());
    }

    // ─────────────────────────────────────────────── EN FRÍO (sin job)

    @Test
    void enFrioActualizaBdRamYGrafo() {
        assertFalse(registro.haySimulacionEnCurso());
        assertTrue(servicio.actualizarCapacidadAeropuerto("SKBO", 1500));

        assertEquals(1500, cargador.getAeropuerto("SKBO").getCapacidad());   // RAM (baseline)
        assertEquals(1500, grafo.nodos.get("SKBO").capacidad);               // grafo
        assertEquals(1500, grafo.nodos.get("SKBO").capacidadAlmacen);
        assertTrue(jdbc.updateCalls > 0, "en frío SÍ escribe BD");
        assertEquals(1000, cargador.getAeropuerto("SKBO").getCapacidadOriginal());   // original intacto
    }

    @Test
    void enFrioActualizaVuelo() {
        assertTrue(servicio.actualizarCapacidadVuelo("SKBO-SEQM-08:30", 450));   // id con dos puntos
        assertEquals(450, vuelo.getCapacidad());
        assertEquals(450, grafo.aristas.get(0).capacidad);
        assertTrue(jdbc.updateCalls > 0);
    }

    // ─────────────────────────────────────────────── EN CALIENTE (job en curso)

    @Test
    void enCalienteSoloGrafoNoTocaBdNiRam() {
        simularJobEnCurso();
        assertTrue(servicio.actualizarCapacidadAeropuerto("SKBO", 1500));

        assertEquals(1500, grafo.nodos.get("SKBO").capacidad);               // grafo SÍ cambia
        assertEquals(1000, cargador.getAeropuerto("SKBO").getCapacidad());   // RAM NO cambia (baseline intacto)
        assertEquals(0, jdbc.updateCalls, "en caliente NO escribe BD");
    }

    @Test
    void enCalienteActualizaVueloSoloGrafo() {
        simularJobEnCurso();
        assertTrue(servicio.actualizarCapacidadVuelo("SKBO-SEQM-0830", 450));

        assertEquals(450, grafo.aristas.get(0).capacidad);   // grafo SÍ
        assertEquals(300, vuelo.getCapacidad());             // RAM NO
        assertEquals(0, jdbc.updateCalls);
    }

    // ─────────────────────────────────────────────── validación y 404

    @Test
    void valorInvalidoLanza400() {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.actualizarCapacidadAeropuerto("SKBO", 0));
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.actualizarCapacidadVuelo("SKBO-SEQM-0830", -3));
        assertEquals(1000, cargador.getAeropuerto("SKBO").getCapacidad());
    }

    @Test
    void inexistenteDevuelveFalse() {
        jdbc.filasAfectadas = 0;   // en frío: UPDATE sin filas
        assertFalse(servicio.actualizarCapacidadAeropuerto("XXXX", 500));
        assertFalse(servicio.actualizarCapacidadVuelo("NADA-NADA-0000", 500));
    }

    // ─────────────────────────────────────────────── resincronización (inicio de corrida)

    @Test
    void resincronizarReponeBaselineFrioYBorraCaliente() {
        // Configuración en frío que debe PERSISTIR
        servicio.actualizarCapacidadAeropuerto("SKBO", 1200);
        // Override en caliente que debe DESAPARECER
        simularJobEnCurso();
        servicio.actualizarCapacidadAeropuerto("SKBO", 1500);
        assertEquals(1500, grafo.nodos.get("SKBO").capacidad);   // caliente aplicado

        servicio.resincronizarCapacidadesConBaselineFrio();

        assertEquals(1200, grafo.nodos.get("SKBO").capacidad);   // vuelve al baseline en frío, no al original
        assertEquals(1200, cargador.getAeropuerto("SKBO").getCapacidad());
    }

    // ─────────────────────────────────────────────── restaurar a fábrica

    @Test
    void restaurarDevuelveTodoAFabrica() {
        servicio.actualizarCapacidadAeropuerto("SKBO", 1500);       // frío
        servicio.actualizarCapacidadVuelo("SKBO-SEQM-0830", 450);   // frío

        servicio.restaurarCapacidadesAFabrica();

        assertEquals(1000, cargador.getAeropuerto("SKBO").getCapacidad());
        assertEquals(1000, grafo.nodos.get("SKBO").capacidad);
        assertEquals(300, vuelo.getCapacidad());
        assertEquals(300, grafo.aristas.get(0).capacidad);
    }

    // ─────────────────────────────────────────────── helpers

    private static Aeropuerto aeropuerto(String icao, int offset, int capacidad) {
        Aeropuerto a = new Aeropuerto();
        a.setCodigo(icao);
        a.setOffsetHorario(offset);
        a.setCapacidad(capacidad);
        a.setCapacidadOriginal(capacidad);
        return a;
    }

    private static Vuelo vuelo(String idVuelo, Aeropuerto origen, Aeropuerto destino, int capacidad) {
        Vuelo v = new Vuelo();
        v.setIdVuelo(idVuelo);
        v.setOrigen(origen.getCodigo());
        v.setDestino(destino.getCodigo());
        v.setAeropuertoOrigen(origen);
        v.setAeropuertoDestino(destino);
        v.setCapacidad(capacidad);
        v.setCapacidadOriginal(capacidad);
        v.setFechaHoraSalida(LocalDateTime.of(2026, 1, 2, 8, 30));
        v.setFechaHoraLlegada(LocalDateTime.of(2026, 1, 2, 10, 30));
        return v;
    }

    private static void inyectar(Object destino, String campo, Object valor) throws Exception {
        Field f = destino.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(destino, valor);
    }
}
