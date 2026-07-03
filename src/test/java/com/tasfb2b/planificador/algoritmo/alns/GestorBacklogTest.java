package com.tasfb2b.planificador.algoritmo.alns;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifica la purga por SLA vencido del backlog, base de la deteccion de colapso
 * por vencimiento real (Politica 1): un envio cuyo {@code readyTime + SLA} ya paso
 * sin entrega on-time pasa a {@code sinRutaDefinitivo}.
 */
class GestorBacklogTest {

    private static LoteEnvio batch(String id, int slaHours, LocalDateTime ready) {
        return new LoteEnvio(id, 5, slaHours, "AAA", "CCC", ready);
    }

    @Test
    void purgarVencidasMueveSoloLosVencidosASinRutaDefinitivo() {
        GestorBacklog backlog = new GestorBacklog(0, true);
        // deadline = 2026-01-02T07:00 (vigente)
        LoteEnvio vigente = batch("V", 24, LocalDateTime.of(2026, 1, 1, 7, 0));
        // deadline = 2026-01-01T09:00 (vencido respecto a scNow=12:00)
        LoteEnvio vencido = batch("X", 2, LocalDateTime.of(2026, 1, 1, 7, 0));
        backlog.addSinRuta(vigente);
        backlog.addSinRuta(vencido);

        int purgados = backlog.purgarVencidas(LocalDateTime.of(2026, 1, 1, 12, 0));

        assertEquals(1, purgados, "solo el envio cuyo deadline ya paso se purga");
        assertEquals(1, backlog.sinRutaDefinitivo());
        assertEquals(1, backlog.size(), "el envio vigente sigue en el backlog para reintentarse");
    }

    @Test
    void pollPendientesUrgentesTomaDeadlineMasCercanoYConservaElResto() {
        GestorBacklog backlog = new GestorBacklog(0, true);
        LocalDateTime ready = LocalDateTime.of(2026, 1, 1, 0, 0);
        LoteEnvio a = batch("A", 24, ready);   // deadline 01-02 00:00
        LoteEnvio b = batch("B", 48, ready);   // deadline 01-03 00:00 (menos urgente)
        LoteEnvio c = batch("C", 2, ready);    // deadline 01-01 02:00 (más urgente)
        backlog.addSinRuta(a);
        backlog.addSinRuta(b);
        backlog.addSinRuta(c);

        // Fase M: pedir 1 → devuelve el de deadline más cercano (C); los otros 2 quedan.
        List<LoteEnvio> urgentes = backlog.pollPendientesUrgentes(1);
        assertEquals(1, urgentes.size());
        assertSame(c, urgentes.get(0));
        assertEquals(2, backlog.size(), "los menos urgentes permanecen en el backlog");

        // El siguiente más urgente es A (24h) antes que B (48h).
        List<LoteEnvio> siguiente = backlog.pollPendientesUrgentes(1);
        assertSame(a, siguiente.get(0));
        assertEquals(1, backlog.size());

        // No se pierde ninguno: 3 entraron, 2 salieron, 1 queda.
        List<LoteEnvio> resto = backlog.pollPendientesUrgentes(0);   // 0 = todos
        assertEquals(1, resto.size());
        assertSame(b, resto.get(0));
        assertEquals(0, backlog.size());
    }

    @Test
    void purgaDesactivadaNoDescartaAunqueElSlaHayaVencido() {
        GestorBacklog backlog = new GestorBacklog(0, false);
        backlog.addSinRuta(batch("X", 2, LocalDateTime.of(2026, 1, 1, 7, 0)));

        int purgados = backlog.purgarVencidas(LocalDateTime.of(2030, 1, 1, 0, 0));

        assertEquals(0, purgados, "con purga desactivada no se descarta nada");
        assertEquals(1, backlog.size());
        assertEquals(0, backlog.sinRutaDefinitivo());
    }
}
