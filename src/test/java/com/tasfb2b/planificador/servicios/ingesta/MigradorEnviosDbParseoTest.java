package com.tasfb2b.planificador.servicios.ingesta;

import com.tasfb2b.planificador.dto.jobs.InyeccionEnviosRequest;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parseo del TXT de envíos por sede ({@code id-YYYYMMDD-HH-MM-DEST-###-IdClien}) a items de inyección.
 * La fecha-hora está en hora LOCAL del aeropuerto origen; el parser la convierte a UTC con el offset
 * (registroUtc = local − offset). Verifica los dos sentidos del huso, la identidad con offset 0, el
 * descarte de líneas malformadas y la propagación de registrador/sede.
 */
class MigradorEnviosDbParseoTest {

    /** VIDP (+5): 02:00 local del 09/07 → 21:00 UTC del 08/07 (cruce de medianoche hacia atrás). */
    @Test
    void offsetPositivoRestaHorasYRuedaLaFechaHaciaAtras() throws Exception {
        List<InyeccionEnviosRequest.Item> items = MigradorEnviosDb.parsearEnviosParaInyeccion(
                new StringReader("001-20260709-02-00-SPIM-05-0007729\n"),
                "VIDP", 5, "Rodrigo", "Delhi");

        assertEquals(1, items.size());
        InyeccionEnviosRequest.Item it = items.get(0);
        assertEquals(LocalDateTime.of(2026, 7, 8, 21, 0), it.getFechaHoraRegistro());
        assertEquals("VIDP", it.getOrigen());
        assertEquals("SPIM", it.getDestino());
        assertEquals(5, it.getCantidad());
        assertEquals(7729, it.getClienteId());
        assertEquals("Rodrigo", it.getRegistrador());
        assertEquals("Delhi", it.getSede());
    }

    /** SPIM (−5): 20:00 local del 09/07 → 01:00 UTC del 10/07 (cruce de medianoche hacia adelante). */
    @Test
    void offsetNegativoSumaHorasYRuedaLaFechaHaciaAdelante() throws Exception {
        List<InyeccionEnviosRequest.Item> items = MigradorEnviosDb.parsearEnviosParaInyeccion(
                new StringReader("002-20260709-20-00-SABE-10-0000001\n"),
                "SPIM", -5, "Ana", "Lima");

        assertEquals(1, items.size());
        assertEquals(LocalDateTime.of(2026, 7, 10, 1, 0), items.get(0).getFechaHoraRegistro());
        assertEquals("SABE", items.get(0).getDestino());
    }

    /** Offset 0: la hora local es directamente la UTC (identidad). */
    @Test
    void offsetCeroEsIdentidad() throws Exception {
        List<InyeccionEnviosRequest.Item> items = MigradorEnviosDb.parsearEnviosParaInyeccion(
                new StringReader("003-20260709-08-30-EKCH-03-0000002\n"),
                "EGLL", 0, null, null);

        assertEquals(1, items.size());
        assertEquals(LocalDateTime.of(2026, 7, 9, 8, 30), items.get(0).getFechaHoraRegistro());
    }

    /** Una línea con menos de 7 campos se descarta sin abortar el resto del archivo. */
    @Test
    void lineaMalformadaSeDescartaSinAbortar() throws Exception {
        List<InyeccionEnviosRequest.Item> items = MigradorEnviosDb.parsearEnviosParaInyeccion(
                new StringReader("basura-sin-campos\n001-20260709-02-00-SPIM-05-0007729\n"),
                "VIDP", 5, "Rodrigo", "Delhi");

        assertEquals(1, items.size(), "solo la línea válida sobrevive");
    }
}
