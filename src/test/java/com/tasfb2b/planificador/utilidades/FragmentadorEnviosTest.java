package com.tasfb2b.planificador.utilidades;

import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fragmentación estática de envíos en sub-lotes ({@link FragmentadorEnvios}). Lógica pura, sin BD
 * ni Spring: reparto exacto y determinista, no-op bajo el umbral, parseo del sufijo {@code -F},
 * tope de sub-lotes, umbral auto desde el grafo y {@code clonar()} que preserva la identidad.
 */
class FragmentadorEnviosTest {

    private static final LocalDateTime READY = LocalDateTime.parse("2026-01-02T07:00:00");

    private static LoteEnvio lote(String id, int cantidad) {
        return new LoteEnvio(id, cantidad, 24, "SKBO", "SEQM", READY);
    }

    // ── Reparto ──────────────────────────────────────────────────────────────

    @Test
    void reparteExactoYPrimerosLlevanElResto() {
        List<LoteEnvio> subs = FragmentadorEnvios.fragmentar(lote("SKBO-000000001", 1001), 500, 64);

        assertEquals(3, subs.size(), "ceil(1001/500) = 3 sub-lotes");
        assertEquals(334, subs.get(0).getCantidad());
        assertEquals(334, subs.get(1).getCantidad());
        assertEquals(333, subs.get(2).getCantidad());
        assertEquals(1001, subs.stream().mapToInt(LoteEnvio::getCantidad).sum(),
                "la suma de sub-lotes conserva la cantidad del padre");
    }

    @Test
    void asignaIdentidadYSufijoAlosSubLotes() {
        List<LoteEnvio> subs = FragmentadorEnvios.fragmentar(lote("SKBO-000000001", 1001), 500, 64);

        for (int i = 0; i < subs.size(); i++) {
            LoteEnvio s = subs.get(i);
            assertEquals("SKBO-000000001-F" + (i + 1), s.getId());
            assertEquals("SKBO-000000001", s.getIdPadre());
            assertEquals(i + 1, s.getFragmento());
            assertEquals(3, s.getTotalFragmentos());
            assertTrue(s.esFragmento());
        }
    }

    @Test
    void copiaClienteYFlagSinteticoAlosSubLotes() {
        LoteEnvio padre = lote("INV-5-0", 900);
        padre.setSintetico(true);
        padre.setClienteId(42);

        List<LoteEnvio> subs = FragmentadorEnvios.fragmentar(padre, 400, 64);

        assertEquals(3, subs.size());
        for (LoteEnvio s : subs) {
            assertTrue(s.isSintetico());
            assertEquals(42, s.getClienteId());
        }
    }

    // ── No-op bajo el umbral ─────────────────────────────────────────────────

    @Test
    void cantidadIgualAlUmbralNoFragmenta() {
        LoteEnvio padre = lote("SKBO-000000009", 500);
        List<LoteEnvio> subs = FragmentadorEnvios.fragmentar(padre, 500, 64);

        assertEquals(1, subs.size());
        assertSame(padre, subs.get(0), "debe devolver el lote intacto, sin copiar");
        assertFalse(subs.get(0).esFragmento());
    }

    @Test
    void cantidadBajoElUmbralNoFragmenta() {
        LoteEnvio padre = lote("SKBO-000000003", 3);
        List<LoteEnvio> subs = FragmentadorEnvios.fragmentar(padre, 500, 64);

        assertEquals(1, subs.size());
        assertSame(padre, subs.get(0));
    }

    // ── Tope de sub-lotes ────────────────────────────────────────────────────

    @Test
    void topeDeSubLotesAcotaElNumeroYPreservaLaSuma() {
        // 100.000 / 100 = 1000 sub-lotes pedidos, pero el tope es 4.
        List<LoteEnvio> subs = FragmentadorEnvios.fragmentar(lote("INV-1-0", 100_000), 100, 4);

        assertEquals(4, subs.size(), "clamp al tope de sub-lotes");
        assertEquals(100_000, subs.stream().mapToInt(LoteEnvio::getCantidad).sum(),
                "aunque se limite, la suma conserva la cantidad total");
        assertTrue(subs.stream().allMatch(s -> s.getCantidad() > 100),
                "con el clamp, cada sub-lote supera el umbral (seguirán sin ruta, como hoy)");
    }

    // ── Parseo del sufijo ────────────────────────────────────────────────────

    @Test
    void reconoceYParseaElSufijoDeSubLote() {
        assertTrue(FragmentadorEnvios.esIdSubLote("SKBO-000000001-F2"));
        assertTrue(FragmentadorEnvios.esIdSubLote("INV-5-0-F1"));
        assertFalse(FragmentadorEnvios.esIdSubLote("SKBO-000000001"));
        assertFalse(FragmentadorEnvios.esIdSubLote("INV-5-0"));

        assertEquals("SKBO-000000001", FragmentadorEnvios.idPadreDe("SKBO-000000001-F2"));
        assertEquals("INV-5-0", FragmentadorEnvios.idPadreDe("INV-5-0-F1"));
        assertEquals("SKBO-000000001", FragmentadorEnvios.idPadreDe("SKBO-000000001"),
                "un id que no es sub-lote es su propio padre");

        assertEquals(2, FragmentadorEnvios.numeroFragmentoDe("SKBO-000000001-F2"));
        assertEquals(0, FragmentadorEnvios.numeroFragmentoDe("SKBO-000000001"));
    }

    // ── Umbral efectivo ──────────────────────────────────────────────────────

    @Test
    void umbralAutoUsaLaCapacidadMaximaDeAvionDelGrafo() {
        PlanificadorProperties.Fragmentacion cfg = new PlanificadorProperties.Fragmentacion();
        cfg.setHabilitada(true);
        cfg.setMaxMaletasPorSublote(0);   // auto

        Grafo g = grafoConCapacidades(200, 350, 300);
        assertEquals(350, FragmentadorEnvios.umbralEfectivo(cfg, g));
    }

    @Test
    void umbralExplicitoTienePrioridadSobreElAuto() {
        PlanificadorProperties.Fragmentacion cfg = new PlanificadorProperties.Fragmentacion();
        cfg.setHabilitada(true);
        cfg.setMaxMaletasPorSublote(250);

        Grafo g = grafoConCapacidades(200, 350, 300);
        assertEquals(250, FragmentadorEnvios.umbralEfectivo(cfg, g));
    }

    @Test
    void deshabilitadaNuncaFragmenta() {
        PlanificadorProperties.Fragmentacion cfg = new PlanificadorProperties.Fragmentacion();
        cfg.setHabilitada(false);

        int umbral = FragmentadorEnvios.umbralEfectivo(cfg, grafoConCapacidades(200, 350, 300));
        assertEquals(Integer.MAX_VALUE, umbral);

        List<LoteEnvio> subs = FragmentadorEnvios.fragmentar(lote("SKBO-000000001", 5000), umbral, 64);
        assertEquals(1, subs.size(), "con la fragmentación off, un envío gigante no se parte");
    }

    // ── clonar() preserva la identidad de fragmentación ──────────────────────

    @Test
    void clonarPreservaLaIdentidadDeFragmentacion() {
        LoteEnvio sub = FragmentadorEnvios.fragmentar(lote("SKBO-000000001", 1001), 500, 64).get(1);
        LoteEnvio copia = sub.clonar();

        assertEquals(sub.getId(), copia.getId());
        assertEquals("SKBO-000000001", copia.getIdPadre());
        assertEquals(2, copia.getFragmento());
        assertEquals(3, copia.getTotalFragmentos());
        assertTrue(copia.esFragmento());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Grafo grafoConCapacidades(int... capacidades) {
        Grafo g = new Grafo();
        int idx = 0;
        for (int cap : capacidades) {
            Arista e = new Arista();
            e.indice = idx++;
            e.capacidad = cap;
            g.agregarArista(e);
        }
        return g;
    }
}
