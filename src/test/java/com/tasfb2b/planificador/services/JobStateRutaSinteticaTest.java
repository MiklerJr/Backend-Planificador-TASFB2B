package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.simulacion.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Índice en RAM de los envíos inyectados/registrados EN VIVO (id sintético {@code INV-*}) que
 * alimenta {@code GET /jobs/{id}/envios/{idEnvio}}: su ruta NO se persiste en BD, así que el rastreo
 * sale de {@link JobState#getRutaSintetica}. Solo los ENRUTADOS se indexan; los de dataset no.
 */
class JobStateRutaSinteticaTest {

    @Test
    void indexaSoloLosSinteticosEnrutados() {
        JobState job = new JobsRegistry().crear("1", 1);

        AsignacionMaleta invEnrutado = asignacion("INV-1-0", true,
                tramo("LA2450", "SKBO", "SPIM"));
        AsignacionMaleta invSinRuta = asignacion("INV-1-1", false);          // registrado, aún sin ruta
        AsignacionMaleta dataset = asignacion("SKBO-000000001", true,
                tramo("LA0001", "SKBO", "SPIM"));

        job.publicarBloque(bloque(1, invEnrutado, invSinRuta, dataset));

        assertSame(invEnrutado, job.getRutaSintetica("INV-1-0"), "el INV-* enrutado se rastrea");
        assertNull(job.getRutaSintetica("INV-1-1"), "el INV-* aún sin ruta NO se rastrea (⇒ 404)");
        assertNull(job.getRutaSintetica("SKBO-000000001"), "los de dataset resuelven por BD, no por RAM");
        assertNull(job.getRutaSintetica("INV-9-9"), "id inexistente");
        assertNull(job.getRutaSintetica(null), "null-safe");
    }

    @Test
    void reEnrutamientoSobrescribeConLaRutaVigente() {
        JobState job = new JobsRegistry().crear("1", 1);

        job.publicarBloque(bloque(1, asignacion("INV-1-0", true, tramo("LA2450", "SKBO", "SPIM"))));
        AsignacionMaleta reEnrutado = asignacion("INV-1-0", true, tramo("LA9999", "SKBO", "SPIM"));
        job.publicarBloque(bloque(2, reEnrutado));

        assertSame(reEnrutado, job.getRutaSintetica("INV-1-0"), "last-write-wins: queda la ruta vigente");
        assertEquals("LA9999", job.getRutaSintetica("INV-1-0").getTramos().get(0).getVueloId());
    }

    @Test
    void liberarPesadosOlvidaLosSinteticos() {
        JobState job = new JobsRegistry().crear("1", 1);
        job.publicarBloque(bloque(1, asignacion("INV-1-0", true, tramo("LA2450", "SKBO", "SPIM"))));

        job.liberarPesados();   // eviction de job terminado

        assertNull(job.getRutaSintetica("INV-1-0"), "el job evictado ya no rastrea sus INV-*");
    }

    private static BloqueSimulacion bloque(int idx, AsignacionMaleta... asignaciones) {
        BloqueSimulacion bloque = new BloqueSimulacion();
        bloque.setBloqueIdx(idx);
        bloque.setHoraInicio("2026-05-19T08:00:00");
        bloque.setHoraFin("2026-05-19T09:00:00");
        bloque.setAsignaciones(List.of(asignaciones));
        return bloque;
    }

    private static AsignacionMaleta asignacion(String batchId, boolean enrutada, TramoRuta... tramos) {
        AsignacionMaleta a = new AsignacionMaleta();
        a.setBatchId(batchId);
        a.setOrigen("SKBO");
        a.setDestino("SPIM");
        a.setCantidad(99);
        a.setEnrutada(enrutada);
        a.setTramos(List.of(tramos));
        return a;
    }

    private static TramoRuta tramo(String vueloId, String origen, String destino) {
        TramoRuta t = new TramoRuta();
        t.setVueloId(vueloId);
        t.setOrigen(origen);
        t.setDestino(destino);
        t.setSalidaUtc("2026-05-19T10:00:00");
        t.setLlegadaUtc("2026-05-19T13:00:00");
        return t;
    }
}
