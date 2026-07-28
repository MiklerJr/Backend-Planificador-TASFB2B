package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.dto.simulacion.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verificación del fix del hallazgo "eje temporal mezclado en maletasEntregadasAcum":
 * {@code llenarAcumuladosFisicos} ya no compara el arribo UTC contra {@code ctx.scFin} (eje de
 * registro LOCAL, mezcla husos, interpretado como si fuese UTC); el corte ahora es el RELOJ UTC
 * de la simulación = máximo {@code readyTime} (UTC) visto en el acumulador — el mismo concepto
 * de reloj que usa {@code reconstruirEsperaOrigenBacklog}. Una entrega cuenta solo cuando su
 * arribo UTC ya ocurrió respecto a ese reloj: la cuenta es física, monótona y nunca incluye
 * entregas futuras.
 *
 * <p>Antes del fix, un corte nominal 23:30 en el eje local de un origen GMT+9 (= 14:30 UTC
 * reales) contaba como entregada una llegada de las 16:00 UTC que aún no había ocurrido.
 */
class AcumuladosFisicosEjeTemporalTest {

    private static final LocalDate DIA = LocalDate.of(2026, 1, 1);

    /**
     * Envío que aterriza a las 16:00 UTC, registrado a las 07:00 UTC. El reloj de la simulación
     * (max readyTime = 07:00 UTC) aún no alcanza el arribo: la entrega NO ha ocurrido y no debe
     * contarse — antes del fix, el corte local adelantado la contaba.
     */
    @Test
    void noCuentaUnaEntregaCuyoArriboAunNoOcurreSegunElRelojUtc() {
        Map<String, LoteEnvio> auditAcc = new LinkedHashMap<>();
        auditAcc.put("B1", batchConArribo(LocalDateTime.of(DIA, LocalTime.of(16, 0))));
        BloqueSimulacion bloque = new BloqueSimulacion();

        llenar(bloque, auditAcc);

        assertEquals(7, bloque.getMaletasProcesadasAcum());
        assertEquals(7, bloque.getMaletasEnrutadasAcum());
        assertEquals(0, bloque.getMaletasEntregadasAcum(),
                "el reloj UTC (max readyTime = 07:00) aún no alcanza el arribo (16:00): "
                        + "la entrega no ha ocurrido físicamente");
    }

    /**
     * Cuando el reloj UTC avanza (entra un registro a las 16:00 UTC, igual al arribo), la
     * entrega ya ocurrió y cuenta — el corte es inclusivo ({@code <=}).
     */
    @Test
    void cuentaLaEntregaCuandoElRelojUtcAlcanzaElArribo() {
        Map<String, LoteEnvio> auditAcc = new LinkedHashMap<>();
        auditAcc.put("B1", batchConArribo(LocalDateTime.of(DIA, LocalTime.of(16, 0))));
        auditAcc.put("B2", batchSinRuta(LocalDateTime.of(DIA, LocalTime.of(16, 0))));
        BloqueSimulacion bloque = new BloqueSimulacion();

        llenar(bloque, auditAcc);

        assertEquals(12, bloque.getMaletasProcesadasAcum(), "7 enrutadas + 5 sin ruta");
        assertEquals(7, bloque.getMaletasEnrutadasAcum());
        assertEquals(7, bloque.getMaletasEntregadasAcum(),
                "el reloj UTC (max readyTime = 16:00) alcanza el arribo (16:00): cuenta");
    }

    /**
     * Control al minuto: con el reloj UTC en 15:59 la misma entrega de las 16:00 NO cuenta.
     * La métrica responde al instante físico real, no al eje local del registro.
     */
    @Test
    void elRelojUtcDistingueAlMinutoSiLaEntregaYaOcurrio() {
        Map<String, LoteEnvio> auditAcc = new LinkedHashMap<>();
        auditAcc.put("B1", batchConArribo(LocalDateTime.of(DIA, LocalTime.of(16, 0))));
        auditAcc.put("B2", batchSinRuta(LocalDateTime.of(DIA, LocalTime.of(15, 59))));
        BloqueSimulacion bloque = new BloqueSimulacion();

        llenar(bloque, auditAcc);

        assertEquals(7, bloque.getMaletasEnrutadasAcum());
        assertEquals(0, bloque.getMaletasEntregadasAcum(),
                "con el reloj UTC en 15:59 una llegada de las 16:00 sigue en el aire");
    }

    // ----------------------------------------------------------------------- helpers

    /** Registra los batches en el AcumuladorAuditoria (Fase 5b) y llena los acumulados físicos del bloque. */
    private static void llenar(BloqueSimulacion bloque, Map<String, LoteEnvio> auditAcc) {
        AcumuladorAuditoria acc = new AcumuladorAuditoria(false);
        for (LoteEnvio b : auditAcc.values()) acc.registrar(b);
        acc.llenarAcumuladosFisicos(bloque);
    }

    /** Batch de 7 maletas (ready 07:00 UTC) con un único tramo de 60 min que aterriza en {@code arriboUtc}. */
    private static LoteEnvio batchConArribo(LocalDateTime arriboUtc) {
        Arista tramo = new Arista();
        tramo.indice = 0;
        tramo.id = "F1";
        tramo.duracionMinutos = 60;
        long depMin = OperadorReparacionVoraz.aMinutoEpochPublico(arriboUtc.minusMinutes(60));

        LoteEnvio b = new LoteEnvio("B1", 7, 24, "AAA", "BBB",
                LocalDateTime.of(DIA, LocalTime.of(7, 0)));
        b.setRutaAsignada(List.of(tramo));
        b.setSalidasAsignadas(List.of(depMin));
        b.setCumpleSLA(true);
        return b;
    }

    /** Batch de 5 maletas sin ruta: solo aporta su {@code readyTime} (UTC) al reloj de la simulación. */
    private static LoteEnvio batchSinRuta(LocalDateTime readyUtc) {
        return new LoteEnvio("B2", 5, 24, "AAA", "BBB", readyUtc);
    }
}
