package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.servicios.AltasEnCalienteService.LineaVueloTxt;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parseo del TXT de planes de vuelo (formato del dataset {@code ORIG-DEST-HH:MM-HH:MM-CAPACIDAD}, horas
 * LOCALES) a altas EN CALIENTE. Comentarios ("*"/"**"/"//"), líneas vacías y cabecera se ignoran EN
 * SILENCIO (no aparecen en el resultado); las malformadas se devuelven con motivo de descarte para que
 * el llamador las reporte sin abortar el lote. La validación profunda la hace la tubería de encolado.
 */
class AltasVueloTxtParserTest {

    @Test
    void ignoraComentariosVaciasYCabeceraEnSilencio() throws Exception {
        String txt = String.join("\n",
                "** vuelos adicionales de la prueba",
                "* otra forma de comentario",
                "// comentario estilo código",
                "",
                "ORIG-DEST-HH:MM-HH:MM-CAP",
                "SPIM-SABE-04:00-08:30-0150");

        List<LineaVueloTxt> lineas = AltasEnCalienteService.parsearAltasVueloTxt(new StringReader(txt));

        assertEquals(1, lineas.size(), "solo la línea de vuelo real sobrevive");
        assertNull(lineas.get(0).motivoDescarte());
    }

    @Test
    void lineaValidaProduceAltaConCapacidadNormalizada() throws Exception {
        List<LineaVueloTxt> lineas = AltasEnCalienteService.parsearAltasVueloTxt(
                new StringReader("SPIM-SABE-04:00-08:30-0150"));

        assertEquals(1, lineas.size());
        LineaVueloTxt l = lineas.get(0);
        assertNull(l.motivoDescarte());
        assertNotNull(l.alta());
        assertEquals("SPIM", l.alta().getOrigen());
        assertEquals("SABE", l.alta().getDestino());
        assertEquals("04:00", l.alta().getHoraSalida());
        assertEquals("08:30", l.alta().getHoraLlegada());
        assertEquals(150, l.alta().getCapacidad(), "el 0150 con cero a la izquierda es 150");
    }

    @Test
    void faltaDeCamposSeDescartaConMotivo() throws Exception {
        List<LineaVueloTxt> lineas = AltasEnCalienteService.parsearAltasVueloTxt(
                new StringReader("SPIM-SABE-04:00-08:30"));

        assertEquals(1, lineas.size());
        assertNull(lineas.get(0).alta());
        assertNotNull(lineas.get(0).motivoDescarte());
    }

    @Test
    void capacidadNoNumericaSeDescartaConMotivo() throws Exception {
        List<LineaVueloTxt> lineas = AltasEnCalienteService.parsearAltasVueloTxt(
                new StringReader("SPIM-SABE-04:00-08:30-ABC"));

        assertEquals(1, lineas.size());
        assertNull(lineas.get(0).alta());
        assertTrue(lineas.get(0).motivoDescarte().contains("capacidad"));
    }

    @Test
    void toleraBomAlInicioDeLinea() throws Exception {
        List<LineaVueloTxt> lineas = AltasEnCalienteService.parsearAltasVueloTxt(
                new StringReader("﻿" + "SPIM-SABE-04:00-08:30-0150"));

        assertEquals(1, lineas.size());
        assertNull(lineas.get(0).motivoDescarte());
        assertEquals("SPIM", lineas.get(0).alta().getOrigen());
    }
}
