package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.RegistroJobs;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.analizador.AnalizadorVuelos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Modificación EN FRÍO del horario de un vuelo existente ({@code PUT .../vuelos/{id}/horario}) y su
 * restauración a fábrica. Verifica los cortes 400/404/409, el renombrado del id al cambiar la salida
 * (invariante id_vuelo ≡ ORIGEN-DESTINO-HHMM de la salida vigente), la actualización de la hora en RAM
 * y la invalidación del grafo cacheado. Usa un {@link JdbcTemplate} falso (sin BD) y datos sintéticos.
 */
class ConfiguracionHorarioVueloTest {

    /** JdbcTemplate falso: cuenta updates, no toca BD y devuelve un queryForList configurable. */
    static class FakeJdbc extends JdbcTemplate {
        int filasAfectadas = 1;
        int updateCalls = 0;
        List<Map<String, Object>> queryResult = new ArrayList<>();
        @Override public int update(String sql, Object... args) { updateCalls++; return filasAfectadas; }
        @Override public List<Map<String, Object>> queryForList(String sql) { return queryResult; }
    }

    private FakeJdbc jdbc;
    private CargadorDatos cargador;
    private MotorGrafoCache motorCache;
    private RegistroJobs registro;
    private ConfiguracionCapacidadesService servicio;
    private Vuelo vuelo;
    private Aeropuerto scel;
    private Aeropuerto sabe;

    @BeforeEach
    void setUp() throws Exception {
        Aeropuerto spim = aeropuerto("SPIM", -5);
        scel = aeropuerto("SCEL", -3);
        sabe = aeropuerto("SABE", -3);
        vuelo = vuelo("SPIM-SCEL-0830", spim, scel, 8, 30, 10, 30);

        List<Aeropuerto> aeropuertos = List.of(spim, scel, sabe);
        List<Vuelo> vuelos = new ArrayList<>(List.of(vuelo));

        jdbc = new FakeJdbc();
        cargador = new CargadorDatos(jdbc);
        inyectar(cargador, "vuelos", vuelos);
        inyectar(cargador, "aeropuertos", aeropuertos);
        Map<String, Aeropuerto> porIcao = new java.util.concurrent.ConcurrentHashMap<>();
        for (Aeropuerto a : aeropuertos) porIcao.put(a.getCodigo(), a);
        inyectar(cargador, "aeropuertoMapCache", porIcao);

        motorCache = new MotorGrafoCache();
        motorCache.obtenerGrafo(() -> new com.tasfb2b.planificador.utilidades.MapeadorAlgoritmo()
                .mapearAGrafo(aeropuertos, vuelos));   // materializa el grafo cacheado
        registro = new RegistroJobs();
        servicio = new ConfiguracionCapacidadesService(jdbc, cargador, motorCache, registro);
    }

    private void simularJobEnCurso() {
        EstadoJob job = registro.crear("2", 1);
        job.estado = "ejecutando";
        assertTrue(registro.haySimulacionEnCurso());
    }

    // ─────────────────────────────────────────────── cortes 400/404/409

    @Test
    void sinParametrosLanza400() {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.actualizarHorarioVuelo("SPIM-SCEL-0830", null, null));
    }

    @Test
    void horaMalformadaLanza400() {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.actualizarHorarioVuelo("SPIM-SCEL-0830", "25:99", null));
    }

    @Test
    void idInexistenteDevuelveNull() {
        assertNull(servicio.actualizarHorarioVuelo("NADA-NADA-0000", "11:00", "19:00"));
        assertEquals(0, jdbc.updateCalls, "no escribe BD si el vuelo no existe");
    }

    @Test
    void simulacionEnCursoLanza409() {
        simularJobEnCurso();
        assertThrows(IllegalStateException.class,
                () -> servicio.actualizarHorarioVuelo("SPIM-SCEL-0830", "11:00", "19:00"));
        assertEquals(0, jdbc.updateCalls, "en caliente no modifica el horario");
    }

    // ─────────────────────────────────────────────── éxito EN FRÍO

    @Test
    void enFrioActualizaRamRenombraIdEInvalidaGrafo() {
        assertNotNull(motorCache.grafoSiExiste());

        String nuevoId = servicio.actualizarHorarioVuelo("SPIM-SCEL-08:30", "11:00", "19:00");

        assertEquals("SPIM-SCEL-1100", nuevoId, "el id se renombra al nuevo HHMM de salida");
        assertEquals("SPIM-SCEL-1100", vuelo.getIdVuelo());
        assertEquals(LocalTime.of(11, 0), vuelo.getFechaHoraSalida().toLocalTime(), "salida en RAM actualizada");
        assertNull(motorCache.grafoSiExiste(), "el grafo cacheado se invalidó");
        assertTrue(jdbc.updateCalls > 0, "en frío escribe BD");
    }

    @Test
    void enFrioSoloLlegadaNoRenombra() {
        String nuevoId = servicio.actualizarHorarioVuelo("SPIM-SCEL-0830", null, "12:00");

        assertEquals("SPIM-SCEL-0830", nuevoId, "sin cambio de salida el id NO cambia");
        assertEquals(LocalTime.of(12, 0), vuelo.getFechaHoraLlegada().toLocalTime());
    }

    // ─────────────────────────────────────────────── destino (PUT .../destino)

    @Test
    void destinoAusenteLanza400() {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.actualizarDestinoVuelo("SPIM-SCEL-0830", "  ", null));
    }

    @Test
    void destinoDesconocidoLanza400() {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.actualizarDestinoVuelo("SPIM-SCEL-0830", "XXXX", null));
        assertEquals(0, jdbc.updateCalls);
    }

    @Test
    void destinoIgualAlOrigenLanza400() {
        assertThrows(ParametroInvalidoException.class,
                () -> servicio.actualizarDestinoVuelo("SPIM-SCEL-0830", "SPIM", null));
    }

    @Test
    void destinoConSimulacionEnCursoLanza409() {
        simularJobEnCurso();
        assertThrows(IllegalStateException.class,
                () -> servicio.actualizarDestinoVuelo("SPIM-SCEL-0830", "SABE", null));
        assertEquals(0, jdbc.updateCalls, "en caliente no modifica el destino");
    }

    @Test
    void destinoIdInexistenteDevuelveNull() {
        assertNull(servicio.actualizarDestinoVuelo("NADA-NADA-0000", "SABE", null));
        assertEquals(0, jdbc.updateCalls);
    }

    @Test
    void enFrioCambiaDestinoRenombraIdYActualizaRam() {
        assertNotNull(motorCache.grafoSiExiste());

        String nuevoId = servicio.actualizarDestinoVuelo("SPIM-SCEL-08:30", "SABE", null);

        assertEquals("SPIM-SABE-0830", nuevoId, "el id se renombra al destino nuevo");
        assertEquals("SPIM-SABE-0830", vuelo.getIdVuelo());
        assertEquals("SABE", vuelo.getDestino());
        assertSame(sabe, vuelo.getAeropuertoDestino(), "el Aeropuerto destino en RAM es el nuevo");
        assertEquals(LocalTime.of(10, 30), vuelo.getFechaHoraLlegada().toLocalTime(),
                "sin parámetro llegada se conserva la hora local vigente");
        assertNull(motorCache.grafoSiExiste(), "el grafo cacheado se invalidó");
        assertTrue(jdbc.updateCalls > 0, "en frío escribe BD");
    }

    @Test
    void destinoConLlegadaExplicitaLaAplica() {
        servicio.actualizarDestinoVuelo("SPIM-SCEL-0830", "SABE", "12:45");
        assertEquals(LocalTime.of(12, 45), vuelo.getFechaHoraLlegada().toLocalTime());
    }

    // ─────────────────────────────────────────────── restaurar a fábrica

    @Test
    void restaurarDevuelveHorariosOriginalesYRenombraDeVuelta() {
        // El vuelo en RAM ya fue "modificado" (id 1100); la BD reporta su original 08:30.
        vuelo.setIdVuelo("SPIM-SCEL-1100");
        vuelo.setFechaHoraSalida(vuelo.getFechaHoraSalida().withHour(11).withMinute(0));
        jdbc.queryResult.add(fila("SPIM-SCEL-1100", "SPIM", "SCEL", "08:30", "10:30"));

        int restaurados = servicio.restaurarHorariosVuelosAFabrica();

        assertEquals(1, restaurados);
        assertEquals("SPIM-SCEL-0830", vuelo.getIdVuelo(), "id restaurado al original");
        assertEquals(LocalTime.of(8, 30), vuelo.getFechaHoraSalida().toLocalTime());
        assertNull(motorCache.grafoSiExiste(), "grafo invalidado");
    }

    @Test
    void restaurarDevuelveDestinoOriginal() {
        // El vuelo en RAM ya fue re-ruteado a SABE; la BD reporta su destino original SCEL.
        vuelo.setIdVuelo("SPIM-SABE-0830");
        vuelo.setDestino("SABE");
        vuelo.setAeropuertoDestino(sabe);
        Map<String, Object> f = fila("SPIM-SABE-0830", "SPIM", "SABE", "08:30", "10:30");
        f.put("icao_destino_original", "SCEL");
        jdbc.queryResult.add(f);

        int restaurados = servicio.restaurarHorariosVuelosAFabrica();

        assertEquals(1, restaurados);
        assertEquals("SPIM-SCEL-0830", vuelo.getIdVuelo(), "id restaurado al destino original");
        assertEquals("SCEL", vuelo.getDestino());
        assertSame(scel, vuelo.getAeropuertoDestino());
        assertNull(motorCache.grafoSiExiste(), "grafo invalidado");
    }

    @Test
    void restaurarSinModificacionesEsNoOp() {
        jdbc.queryResult = new ArrayList<>();   // ningún vuelo modificado
        assertEquals(0, servicio.restaurarHorariosVuelosAFabrica());
        assertNotNull(motorCache.grafoSiExiste(), "no invalida si no hubo cambios");
    }

    @Test
    void restaurarConSimulacionEnCursoLanza409() {
        simularJobEnCurso();
        assertThrows(IllegalStateException.class, () -> servicio.restaurarHorariosVuelosAFabrica());
    }

    // ─────────────────────────────────────────────── helpers

    private static Map<String, Object> fila(String id, String origen, String destino,
                                            String salidaOrig, String llegadaOrig) {
        Map<String, Object> m = new HashMap<>();
        m.put("id_vuelo", id);
        m.put("icao_origen", origen);
        m.put("icao_destino", destino);
        m.put("hora_salida_original", salidaOrig);
        m.put("hora_llegada_original", llegadaOrig);
        return m;
    }

    private static Aeropuerto aeropuerto(String icao, int offset) {
        Aeropuerto a = new Aeropuerto();
        a.setCodigo(icao);
        a.setOffsetHorario(offset);
        a.setCapacidad(999);
        a.setCapacidadOriginal(999);
        return a;
    }

    private static Vuelo vuelo(String id, Aeropuerto o, Aeropuerto d,
                              int hSal, int mSal, int hLle, int mLle) {
        Vuelo v = new Vuelo();
        v.setIdVuelo(id);
        v.setOrigen(o.getCodigo());
        v.setDestino(d.getCodigo());
        v.setCapacidad(300);
        v.setCapacidadOriginal(300);
        v.setAeropuertoOrigen(o);
        v.setAeropuertoDestino(d);
        v.setFechaHoraSalida(java.time.LocalDateTime.of(AnalizadorVuelos.FLIGHT_BASE_DATE, LocalTime.of(hSal, mSal)));
        v.setFechaHoraLlegada(java.time.LocalDateTime.of(AnalizadorVuelos.FLIGHT_BASE_DATE, LocalTime.of(hLle, mLle)));
        return v;
    }

    private static void inyectar(Object destino, String campo, Object valor) throws Exception {
        Field f = destino.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(destino, valor);
    }
}
