package com.tasfb2b.planificador.utilidades;

import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.utilidades.validador.ValidadorVuelo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coherencia de vuelos al cargar: capacidad &gt; 0 y origen ≠ destino. Las horas de salida y
 * llegada pueden diferir (vuelos que cruzan de un día a otro son válidos).
 */
class ValidadorVueloTest {

    private static Vuelo vuelo(Integer capacidad, String origen, String destino) {
        Vuelo v = new Vuelo();
        v.setCapacidad(capacidad);
        v.setOrigen(origen);
        v.setDestino(destino);
        v.setFechaHoraSalida(LocalDateTime.of(2026, 1, 1, 22, 0));
        v.setFechaHoraLlegada(LocalDateTime.of(2026, 1, 2, 6, 0));
        return v;
    }

    @Test
    void vueloNormalEsCoherente() {
        assertTrue(ValidadorVuelo.esCoherente(vuelo(250, "SKBO", "SEQM")));
    }

    @Test
    void vueloDeUnDiaAOtroEsCoherente() {
        // Salida 23:30 del día 1, llegada 05:10 del día 2: válido, no se valida relación de horas.
        Vuelo v = vuelo(100, "SKBO", "LATI");
        v.setFechaHoraSalida(LocalDateTime.of(2026, 1, 1, 23, 30));
        v.setFechaHoraLlegada(LocalDateTime.of(2026, 1, 2, 5, 10));
        assertTrue(ValidadorVuelo.esCoherente(v));
    }

    @Test
    void capacidadCeroONegativaNoEsCoherente() {
        assertFalse(ValidadorVuelo.esCoherente(vuelo(0, "SKBO", "SEQM")));
        assertFalse(ValidadorVuelo.esCoherente(vuelo(-5, "SKBO", "SEQM")));
    }

    @Test
    void capacidadNulaNoEsCoherente() {
        assertFalse(ValidadorVuelo.esCoherente(vuelo(null, "SKBO", "SEQM")));
    }

    @Test
    void origenIgualADestinoNoEsCoherente() {
        assertFalse(ValidadorVuelo.esCoherente(vuelo(250, "SKBO", "SKBO")));
    }

    @Test
    void vueloNuloNoEsCoherente() {
        assertFalse(ValidadorVuelo.esCoherente(null));
    }
}
