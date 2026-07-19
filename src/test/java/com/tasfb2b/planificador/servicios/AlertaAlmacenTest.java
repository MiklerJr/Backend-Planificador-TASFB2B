package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.dto.almacenes.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code construirAlertaAlmacen}: la alerta de almacén por bloque toma el almacén de mayor % de
 * ocupación y reusa su semáforo; lista vacía o null ⇒ nivel "VERDE" sin almacén crítico.
 */
class AlertaAlmacenTest {

    private static OcupacionAlmacen ocup(String aeropuerto, double porcentaje, String semaforo) {
        OcupacionAlmacen o = new OcupacionAlmacen();
        o.setAeropuerto(aeropuerto);
        o.setPorcentajeOcupacion(porcentaje);
        o.setSemaforo(semaforo);
        o.setCapacidadMaxima(430);
        o.setOcupacionAsignada((int) Math.round(430 * porcentaje / 100.0));
        return o;
    }

    @Test
    void eligeElAlmacenDeMayorPorcentaje() {
        AlertaAlmacen alerta = TelemetriaSimulacionService.construirAlertaAlmacen(List.of(
                ocup("SKBO", 40.0, "VERDE"),
                ocup("SEQM", 95.0, "ROJO"),
                ocup("LATI", 72.0, "AMBAR")), 7);
        assertEquals("SEQM", alerta.getAlmacenCritico());
        assertEquals("ROJO", alerta.getNivel());
        assertEquals(95.0, alerta.getPorcentajeOcupacion());
        assertEquals(7, alerta.getBloqueIdx());
    }

    @Test
    void listaVaciaDaVerdeSinCritico() {
        AlertaAlmacen alerta = TelemetriaSimulacionService.construirAlertaAlmacen(List.of(), 3);
        assertEquals("VERDE", alerta.getNivel());
        assertNull(alerta.getAlmacenCritico());
        assertEquals(3, alerta.getBloqueIdx());
    }

    @Test
    void nullDaVerdeSinCritico() {
        AlertaAlmacen alerta = TelemetriaSimulacionService.construirAlertaAlmacen(null, 0);
        assertEquals("VERDE", alerta.getNivel());
        assertNull(alerta.getAlmacenCritico());
    }
}
