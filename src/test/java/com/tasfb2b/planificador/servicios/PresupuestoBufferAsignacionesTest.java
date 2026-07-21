package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.dto.simulacion.AsignacionMaleta;
import com.tasfb2b.planificador.dto.simulacion.BloqueSimulacion;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 — el buffer de bloques del job se presupuesta por ASIGNACIONES retenidas, no solo por número
 * de bloques: al final del dataset un bloque de 12 h trae ~11.800 asignaciones y 35 bloques
 * retenían cientos de miles. {@code max-bloques-buffer} sigue como tope secundario y el contrato
 * de polling incremental no cambia (un {@code desde} ya purgado devuelve vacío, nunca bloques
 * viejos realineados en silencio).
 */
class PresupuestoBufferAsignacionesTest {

    @Test
    void elTopePorAsignacionesPurgaAntesQueElDeBloques() {
        EstadoJob job = new EstadoJob("j1", "2", 1);
        job.setMaxBloquesConAsignaciones(50);
        job.setMaxAsignacionesBuffer(250);

        for (int i = 0; i < 6; i++) job.publicarBloque(bloque(i, 100));

        assertTrue(job.asignacionesRetenidas() <= 250,
                "el buffer nunca excede el presupuesto de asignaciones: " + job.asignacionesRetenidas());
        assertEquals(6, job.bloquesPublicados(), "los bloques publicados se siguen contando todos");
        assertEquals(2, job.bloquesDesde(job.primerBloqueDisponible()).size(),
                "con 250 asignaciones de presupuesto caben 2 bloques de 100");
        assertEquals(4, job.primerBloqueDisponible());
    }

    @Test
    void siempreQuedaElUltimoBloqueAunqueSoloElYaExcedaElPresupuesto() {
        EstadoJob job = new EstadoJob("j2", "2", 1);
        job.setMaxBloquesConAsignaciones(50);
        job.setMaxAsignacionesBuffer(10);

        job.publicarBloque(bloque(0, 5000));
        job.publicarBloque(bloque(1, 5000));

        assertEquals(1, job.bloquesDesde(0).size(), "el bloque recién publicado nunca se purga");
        assertEquals(1, job.primerBloqueDisponible());
    }

    @Test
    void elTopeDeBloquesSigueVigenteComoLimiteSecundario() {
        EstadoJob job = new EstadoJob("j3", "2", 1);
        job.setMaxBloquesConAsignaciones(3);
        job.setMaxAsignacionesBuffer(1_000_000);

        for (int i = 0; i < 5; i++) job.publicarBloque(bloque(i, 10));

        assertEquals(3, job.bloquesDesde(0).size());
        assertEquals(2, job.primerBloqueDisponible());
    }

    @Test
    void unDesdePurgadoNoDevuelveBloquesViejosRealineados() {
        EstadoJob job = new EstadoJob("j4", "2", 1);
        job.setMaxBloquesConAsignaciones(50);
        job.setMaxAsignacionesBuffer(150);

        for (int i = 0; i < 5; i++) job.publicarBloque(bloque(i, 100));

        assertTrue(job.bloquesDesdeExacto(0).isEmpty(),
                "el front detecta el hueco por lista vacía + primerBloqueDisponible");
        assertFalse(job.bloquesDesdeExacto(job.primerBloqueDisponible()).isEmpty());
    }

    private static BloqueSimulacion bloque(int idx, int asignaciones) {
        BloqueSimulacion b = new BloqueSimulacion();
        b.setBloqueIdx(idx);
        List<AsignacionMaleta> lista = new ArrayList<>(asignaciones);
        for (int i = 0; i < asignaciones; i++) {
            AsignacionMaleta a = new AsignacionMaleta();
            a.setBatchId("E-" + idx + "-" + i);
            lista.add(a);
        }
        b.setAsignaciones(lista);
        return b;
    }
}
