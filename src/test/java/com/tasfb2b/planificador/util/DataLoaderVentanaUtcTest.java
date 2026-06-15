package com.tasfb2b.planificador.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica el corazón del cursor de ventanas en UTC: {@link DataLoader#registroEnVentanaUtc}
 * decide la pertenencia de un envío por su instante UTC real ({@code registroLocal − offset}),
 * no por su hora de pared local. Esto es lo que hace que los {@code BloqueSimulacion} sean UTC
 * contiguos (ventanas adyacentes particionan la demanda sin solapes ni huecos).
 *
 * <p>Husos reales del dataset: Delhi/Karachi GMT+5, Lima/Bogotá/Quito GMT−5 (spread máx. 10 h).
 */
class DataLoaderVentanaUtcTest {

    /**
     * Dos envíos con la MISMA hora local pero husos opuestos (+5 y −5) tienen instantes UTC que
     * distan 10 h: caen en ventanas UTC distintas. El filtro es por UTC, no por el reloj de pared.
     */
    @Test
    void mismaHoraLocalEnHusosOpuestosCaeEnVentanasUtcDistintas() {
        LocalDateTime localMedianoche = LocalDateTime.parse("2026-01-02T00:00");

        // Delhi (+5): registroUtc = 2026-01-01T19:00.
        // Lima  (−5): registroUtc = 2026-01-02T05:00.
        LocalDateTime desde = LocalDateTime.parse("2026-01-01T19:00");
        LocalDateTime hasta = LocalDateTime.parse("2026-01-01T19:05");

        assertTrue(DataLoader.registroEnVentanaUtc(localMedianoche, 5, desde, hasta),
                "Delhi (+5) registrado 00:00 local pertenece a la ventana UTC 19:00–19:05 del día previo");
        assertFalse(DataLoader.registroEnVentanaUtc(localMedianoche, -5, desde, hasta),
                "Lima (−5) registrado 00:00 local cae 10 h después (05:00 UTC): fuera de la ventana");
    }

    /** Frontera inferior inclusiva, superior exclusiva. */
    @Test
    void fronteraInferiorInclusivaSuperiorExclusiva() {
        LocalDateTime desde = LocalDateTime.parse("2026-03-10T12:00");
        LocalDateTime hasta = LocalDateTime.parse("2026-03-10T12:05");

        // offset 0 ⇒ registroUtc == registroLocal.
        assertTrue(DataLoader.registroEnVentanaUtc(desde, 0, desde, hasta), "el límite inferior pertenece");
        assertFalse(DataLoader.registroEnVentanaUtc(hasta, 0, desde, hasta), "el límite superior NO pertenece");
    }

    /**
     * Contigüidad del cursor: con ventanas adyacentes A=[t0,t1) y B=[t1,t2), un envío cuyo
     * registroUtc cae justo en la frontera {@code t1} pertenece SOLO a B. No hay solape ni hueco
     * ⇒ los bloques son UTC contiguos (horaFin[A] == horaInicio[B]).
     */
    @Test
    void ventanasAdyacentesParticionanLaDemandaSinSolapeNiHueco() {
        LocalDateTime t0 = LocalDateTime.parse("2026-01-01T19:00");
        LocalDateTime t1 = LocalDateTime.parse("2026-01-01T19:05");
        LocalDateTime t2 = LocalDateTime.parse("2026-01-01T19:10");

        // Envío de Delhi (+5) registrado 00:05 local ⇒ registroUtc = 2026-01-01T19:05 = t1.
        LocalDateTime localFrontera = LocalDateTime.parse("2026-01-02T00:05");

        boolean enA = DataLoader.registroEnVentanaUtc(localFrontera, 5, t0, t1);
        boolean enB = DataLoader.registroEnVentanaUtc(localFrontera, 5, t1, t2);

        assertFalse(enA, "la frontera t1 no pertenece a la ventana A (superior exclusiva)");
        assertTrue(enB, "la frontera t1 pertenece a la ventana B (inferior inclusiva)");
    }
}
