package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.dto.datos.AltaAeropuertoRequest;
import com.tasfb2b.planificador.dto.vuelos.AltaVueloRequest;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import com.tasfb2b.planificador.utilidades.MapeadorAlgoritmo;
import com.tasfb2b.planificador.utilidades.analizador.AnalizadorVuelos;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ciclo alta EN CALIENTE → reversión sin BD (jdbcTemplate null): al aplicar, RAM/grafo crecen; al
 * revertir vuelven al baseline. El invariante posicional 1:1 (vuelos.get(i) ↔ aristas.get(i) con
 * indice==i) —del que dependen las ediciones de capacidad— debe quedar restaurado.
 */
class AltasEnCalienteReversionTest {

    @Test
    void altaVueloYReversionRestauranElBaseline() {
        Fixture f = new Fixture();

        AltaVueloRequest alta = new AltaVueloRequest();
        alta.setOrigen("SAAA");
        alta.setDestino("SBBB");
        alta.setHoraSalida("09:00");   // distinto del baseline (08:00) ⇒ id nuevo
        alta.setHoraLlegada("10:00");
        alta.setCapacidad(40);

        assertNull(f.svc.aplicarAltaVuelo(alta, f.grafo, f.enrutador), "el alta se aplica");
        assertEquals(2, f.cargador.getVuelos().size(), "RAM: 1 baseline + 1 efímero");
        assertEquals(2, f.grafo.aristas.size(), "grafo: 1 baseline + 1 efímero");
        verificarInvariante1a1(f);
        assertTrue(f.cargador.getVuelos().get(1).isEfimero(), "el vuelo agregado va al final y es efímero");

        f.svc.revertirAltasEfimeras();
        assertEquals(1, f.cargador.getVuelos().size(), "RAM vuelve al baseline");
        assertEquals(1, f.grafo.aristas.size(), "grafo vuelve al baseline");
        assertFalse(f.cargador.getVuelos().get(0).isEfimero(), "solo queda el baseline");
        verificarInvariante1a1(f);

        f.svc.revertirAltasEfimeras();   // idempotente
        assertEquals(1, f.cargador.getVuelos().size());
    }

    @Test
    void altaAeropuertoYVueloHaciaElYReversion() {
        Fixture f = new Fixture();

        AltaAeropuertoRequest altaAero = new AltaAeropuertoRequest();
        altaAero.setIcao("SCCC");
        altaAero.setHusoHorario(-5);
        altaAero.setCapacidad(300);
        altaAero.setContinente("AM");
        assertNull(f.svc.aplicarAltaAeropuerto(altaAero, f.grafo, f.enrutador), "aeropuerto agregado");
        assertEquals(3, f.cargador.getAeropuertos().size());
        assertTrue(f.grafo.nodos.containsKey("SCCC"), "el nodo nuevo está en el grafo");

        AltaVueloRequest altaVuelo = new AltaVueloRequest();
        altaVuelo.setOrigen("SBBB");
        altaVuelo.setDestino("SCCC");
        altaVuelo.setHoraSalida("11:00");
        altaVuelo.setHoraLlegada("12:30");
        altaVuelo.setCapacidad(50);
        assertNull(f.svc.aplicarAltaVuelo(altaVuelo, f.grafo, f.enrutador), "vuelo hacia el aeropuerto nuevo");
        assertEquals(2, f.grafo.aristas.size());
        verificarInvariante1a1(f);

        f.svc.revertirAltasEfimeras();
        assertEquals(2, f.cargador.getAeropuertos().size(), "el aeropuerto efímero se fue");
        assertFalse(f.grafo.nodos.containsKey("SCCC"), "el nodo efímero se quitó del grafo");
        assertEquals(1, f.grafo.aristas.size(), "el vuelo efímero se recortó");
        assertNull(f.cargador.getAeropuerto("SCCC"), "el mapa de aeropuertos ya no lo tiene");
        verificarInvariante1a1(f);
    }

    private static void verificarInvariante1a1(Fixture f) {
        assertEquals(f.cargador.getVuelos().size(), f.grafo.aristas.size(),
                "misma cantidad de vuelos en RAM y aristas en el grafo");
        for (int i = 0; i < f.grafo.aristas.size(); i++) {
            assertEquals(i, f.grafo.aristas.get(i).indice,
                    "la arista en la posición " + i + " debe tener indice " + i + " (mapeo posicional 1:1)");
        }
    }

    // ----------------------------------------------------------------------- fixture
    private static final class Fixture {
        final CargadorDatos cargador = new CargadorDatos(null);
        final MotorGrafoCache motorCache = new MotorGrafoCache();
        final Grafo grafo;
        final OperadorReparacionVoraz enrutador;
        final AltasEnCalienteService svc;

        Fixture() {
            Aeropuerto saaa = aeropuerto("SAAA", -5);
            Aeropuerto sbbb = aeropuerto("SBBB", -3);
            cargador.agregarAeropuertoEfimero(saaa);   // efimero=false ⇒ baseline
            cargador.agregarAeropuertoEfimero(sbbb);
            cargador.agregarVueloEfimero(vueloBaseline("SAAA-SBBB-0800", saaa, sbbb, 8, 0, 10, 0));

            MapeadorAlgoritmo mapper = new MapeadorAlgoritmo();
            grafo = motorCache.obtenerGrafo(
                    () -> mapper.mapearAGrafo(cargador.getAeropuertos(), cargador.getVuelos()));
            enrutador = new OperadorReparacionVoraz(grafo, motorCache.cacheEsqueletos());
            svc = new AltasEnCalienteService(null, cargador, motorCache);
        }
    }

    private static Aeropuerto aeropuerto(String codigo, int offset) {
        Aeropuerto a = new Aeropuerto();
        a.setCodigo(codigo);
        a.setOffsetHorario(offset);
        a.setCapacidad(500);
        a.setCapacidadOriginal(500);
        a.setContinente(CargadorDatos.continentePorIcao(codigo));
        return a;   // efimero por defecto false
    }

    private static Vuelo vueloBaseline(String id, Aeropuerto o, Aeropuerto d,
                                       int hSal, int mSal, int hLle, int mLle) {
        Vuelo v = new Vuelo();
        v.setIdVuelo(id);
        v.setOrigen(o.getCodigo());
        v.setDestino(d.getCodigo());
        v.setCapacidad(100);
        v.setCapacidadOriginal(100);
        LocalDateTime salida = LocalDateTime.of(AnalizadorVuelos.FLIGHT_BASE_DATE, java.time.LocalTime.of(hSal, mSal));
        v.setFechaHoraSalida(salida);
        v.setFechaHoraLlegada(LocalDateTime.of(AnalizadorVuelos.FLIGHT_BASE_DATE, java.time.LocalTime.of(hLle, mLle)));
        v.setAeropuertoOrigen(o);
        v.setAeropuertoDestino(d);
        return v;   // efimero por defecto false
    }
}
