package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.dto.AsignacionMaleta;
import com.tasfb2b.planificador.dto.EnvioEstadoResponse;
import com.tasfb2b.planificador.dto.TramoRuta;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Clasificación de estado de un envío y de sus tramos contra un instante de referencia.
 * Lógica pura ({@link EnvioEstadoCalculator}), sin BD ni Spring.
 *
 * <p>Ruta de prueba (2 tramos, UTC): SKBO →[10:00-12:00]→ SPIM →[14:00-16:00]→ SEQM,
 * con una escala en SPIM entre las 12:00 y las 14:00.
 */
class EnvioEstadoCalculatorTest {

    private static LocalDateTime t(String iso) { return LocalDateTime.parse(iso); }

    private static TramoRuta tramo(String origen, String destino, String salida, String llegada) {
        TramoRuta tr = new TramoRuta();
        tr.setOrigen(origen);
        tr.setDestino(destino);
        tr.setSalidaUtc(salida);
        tr.setLlegadaUtc(llegada);
        return tr;
    }

    /** Envío nuevo en cada test: {@code calcular} muta el estado de los tramos. */
    private static AsignacionMaleta envio() {
        AsignacionMaleta a = new AsignacionMaleta();
        a.setBatchId("SKBO-1");
        a.setEnrutada(true);
        a.setTramos(new ArrayList<>(List.of(
                tramo("SKBO", "SPIM", "2026-01-03T10:00", "2026-01-03T12:00"),
                tramo("SPIM", "SEQM", "2026-01-03T14:00", "2026-01-03T16:00"))));
        return a;
    }

    @Test
    void antesDelPrimerVuelo_programadoEnOrigen() {
        AsignacionMaleta a = envio();
        EnvioEstadoResponse r = EnvioEstadoCalculator.calcular(a, t("2026-01-03T09:00"));

        assertEquals(EnvioEstadoCalculator.E_PROGRAMADO, r.getEstado());
        assertEquals("SKBO", r.getUbicacionActual());
        assertNull(r.getTramoActualIdx());
        assertEquals(0, r.getTramosCompletados());
        assertEquals(2, r.getTramosTotales());
        assertEquals("2026-01-03T16:00", r.getLlegadaFinalUtc());
        assertEquals(EnvioEstadoCalculator.PENDIENTE, a.getTramos().get(0).getEstado());
        assertEquals(EnvioEstadoCalculator.PENDIENTE, a.getTramos().get(1).getEstado());
    }

    @Test
    void durantePrimerVuelo_enVuelo() {
        AsignacionMaleta a = envio();
        EnvioEstadoResponse r = EnvioEstadoCalculator.calcular(a, t("2026-01-03T11:00"));

        assertEquals(EnvioEstadoCalculator.E_EN_VUELO, r.getEstado());
        assertNull(r.getUbicacionActual());          // en el aire
        assertEquals(Integer.valueOf(0), r.getTramoActualIdx());
        assertEquals(0, r.getTramosCompletados());
        assertEquals(EnvioEstadoCalculator.EN_CURSO, a.getTramos().get(0).getEstado());
        assertEquals(EnvioEstadoCalculator.PENDIENTE, a.getTramos().get(1).getEstado());
    }

    @Test
    void entreTramos_enEscala() {
        AsignacionMaleta a = envio();
        EnvioEstadoResponse r = EnvioEstadoCalculator.calcular(a, t("2026-01-03T13:00"));

        assertEquals(EnvioEstadoCalculator.E_EN_ESCALA, r.getEstado());
        assertEquals("SPIM", r.getUbicacionActual());
        assertNull(r.getTramoActualIdx());
        assertEquals(1, r.getTramosCompletados());
        assertEquals(EnvioEstadoCalculator.COMPLETADO, a.getTramos().get(0).getEstado());
        assertEquals(EnvioEstadoCalculator.PENDIENTE, a.getTramos().get(1).getEstado());
    }

    @Test
    void justoAlAterrizarElPrimero_cuentaComoEscala() {
        // Borde: ahora == llegada del tramo 0 (12:00) ⇒ tramo 0 COMPLETADO y envío en escala.
        AsignacionMaleta a = envio();
        EnvioEstadoResponse r = EnvioEstadoCalculator.calcular(a, t("2026-01-03T12:00"));

        assertEquals(EnvioEstadoCalculator.E_EN_ESCALA, r.getEstado());
        assertEquals("SPIM", r.getUbicacionActual());
        assertEquals(EnvioEstadoCalculator.COMPLETADO, a.getTramos().get(0).getEstado());
    }

    @Test
    void despuesDelUltimo_entregado() {
        AsignacionMaleta a = envio();
        EnvioEstadoResponse r = EnvioEstadoCalculator.calcular(a, t("2026-01-03T16:30"));

        assertEquals(EnvioEstadoCalculator.E_ENTREGADO, r.getEstado());
        assertEquals("SEQM", r.getUbicacionActual());
        assertEquals(2, r.getTramosCompletados());
        assertEquals(EnvioEstadoCalculator.COMPLETADO, a.getTramos().get(0).getEstado());
        assertEquals(EnvioEstadoCalculator.COMPLETADO, a.getTramos().get(1).getEstado());
    }

    @Test
    void sinInstante_desconocido() {
        AsignacionMaleta a = envio();
        EnvioEstadoResponse r = EnvioEstadoCalculator.calcular(a, null);

        assertEquals(EnvioEstadoCalculator.E_DESCONOCIDO, r.getEstado());
        assertNull(r.getInstanteReferencia());
        assertEquals(2, r.getTramosTotales());
        assertEquals("2026-01-03T16:00", r.getLlegadaFinalUtc());
        assertNull(a.getTramos().get(0).getEstado());   // sin clasificar
    }
}
