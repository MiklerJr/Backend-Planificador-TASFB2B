package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.dataset.DemandaResumenResponse;
import com.tasfb2b.planificador.util.DataLoader;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Anti-OOM de {@code GET /demanda/resumen}: la agregación se hace en BD ({@link DataLoader#agregarDemandaEnRango})
 * y el rango se ACOTA defensivamente al span máximo configurado. Aquí se sustituye {@link DataLoader}
 * por un stub (sin BD) que reporta el rango del dataset y captura con qué ventana se invocó la
 * agregación, para verificar la guarda y que los totales se arman desde las filas O→D (no envío a envío).
 */
class DemandaResumenRangoTest {

    @Test
    void sinRangoAcotaElSpanAlMaximoConfigurado() {
        final LocalDateTime[] cap = new LocalDateTime[2];
        DataLoader stub = new DataLoader(null) {
            @Override public LocalDateTime getPrimeraVentana() { return LocalDateTime.parse("2026-01-02T00:00"); }
            @Override public LocalDateTime getUltimaVentana() { return LocalDateTime.parse("2029-01-05T00:00"); }
            @Override public List<DataLoader.DemandaAgrupada> agregarDemandaEnRango(LocalDateTime d, LocalDateTime h) {
                cap[0] = d; cap[1] = h;
                return List.of(new DataLoader.DemandaAgrupada("SKBO", "SEQM", 10, 25),
                               new DataLoader.DemandaAgrupada("SEQM", "SKBO", 4, 9));
            }
        };
        DatasetMetadataService svc = new DatasetMetadataService(stub);   // default 31 días

        DemandaResumenResponse r = svc.getDemandaResumen(null, null, 20);

        // El rango efectivo se acota a primera + 31 días (no carga el dataset entero de ~3 años).
        assertEquals(LocalDateTime.parse("2026-01-02T00:00"), cap[0]);
        assertEquals(LocalDateTime.parse("2026-02-02T00:00"), cap[1]);
        assertEquals("2026-02-02T00:00", r.getHasta());
        // Totales armados desde las filas agregadas O→D.
        assertEquals(14, r.getTotalEnvios());     // 10 + 4
        assertEquals(34L, r.getTotalMaletas());   // 25 + 9
        assertEquals(2, r.getPorOD().size());
    }

    @Test
    void rangoExplicitoDentroDelSpanSeRespeta() {
        final LocalDateTime[] cap = new LocalDateTime[2];
        DataLoader stub = new DataLoader(null) {
            @Override public LocalDateTime getPrimeraVentana() { return LocalDateTime.parse("2026-01-02T00:00"); }
            @Override public LocalDateTime getUltimaVentana() { return LocalDateTime.parse("2029-01-05T00:00"); }
            @Override public List<DataLoader.DemandaAgrupada> agregarDemandaEnRango(LocalDateTime d, LocalDateTime h) {
                cap[0] = d; cap[1] = h;
                return List.of();
            }
        };
        DatasetMetadataService svc = new DatasetMetadataService(stub);

        LocalDateTime desde = LocalDateTime.parse("2026-03-01T00:00");
        LocalDateTime hasta = LocalDateTime.parse("2026-03-05T00:00");   // span de 4 días < 31
        DemandaResumenResponse r = svc.getDemandaResumen(desde, hasta, 20);

        assertEquals(desde, cap[0]);
        assertEquals(hasta, cap[1]);                 // dentro del span ⇒ intacto
        assertEquals("2026-03-05T00:00", r.getHasta());
    }
}
