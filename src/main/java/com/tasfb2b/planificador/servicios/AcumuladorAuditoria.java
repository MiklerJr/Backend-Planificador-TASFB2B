package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.dto.simulacion.BloqueSimulacion;
import com.tasfb2b.planificador.utilidades.FormatoSimulacion;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Acumulador de auditoría por corrida (Fase 5b): registra el último estado conocido de cada
 * envío ÚNICO (deduplicado por id de lote) para las métricas globales y los sin-ruta que
 * alimenta la auditoría diferida ({@code EstadoJob.auditoriaSinRuta}). En modo
 * {@code retenerBatches} conserva los lotes completos (warm-up / estado inicial).
 *
 * <p><b>Compacto e incremental (P1, reducción de RAM)</b>: el estado por envío es un
 * {@code long} empaquetado ({@link #empacar}) bajo el id del lote —un {@code String} que ya
 * existe, en vez de una clave compuesta creada en cada {@code registrar()}— y los totales se
 * mantienen por deltas al registrar, en vez de recorrer el mapa entero dos veces por bloque
 * (era O(nº envíos únicos) de CPU robada al Ta, creciendo toda la corrida).
 *
 * <p>La deduplicación por id es equivalente a la clave compuesta histórica
 * ({@code id|origen|destino|tiempoListo|cantidad}) porque los cinco campos son inmutables en
 * {@link LoteEnvio} y el id ya los determina: {@code envio.id_envio} es PK del dataset, los
 * sub-lotes de fragmentación llevan sufijo {@code -Fn} y los envíos inyectados en vivo
 * {@code INV-…} son únicos por corrida.
 */
final class AcumuladorAuditoria {
    /** id del lote → estado empaquetado (ver {@link #empacar}). */
    private final Map<String, Long> resumen = new HashMap<>();
    private final Map<String, LoteEnvio> sinRuta = new LinkedHashMap<>();
    private final Map<String, LoteEnvio> completos;

    private int enrutadas;
    private int cumpleSla;
    private long maletas;
    private long maletasEnrutadas;
    private long maletasEntregadas;
    /** Corte del eje temporal: máximo {@code readyMin} visto (monótono, como el max histórico). */
    private long corteMin = Long.MIN_VALUE;

    /** Arribos aún no "graduados" contra {@link #corteMin}, en orden de arribo (lazy deletion). */
    private final PriorityQueue<ArriboPendiente> pendientesEntrega =
            new PriorityQueue<>((a, b) -> Long.compare(a.arriboMin, b.arriboMin));

    AcumuladorAuditoria(boolean retenerBatches) {
        this.completos = retenerBatches ? new LinkedHashMap<>() : null;
    }

    private record ArriboPendiente(long arriboMin, String clave) {}

    // ── Empaquetado del estado por envío ────────────────────────────────────────────────
    // bits  0..30 : arriboMin (minuto epoch; SIN_ARRIBO si el lote no tiene arribo calculable)
    // bits 31..60 : cantidad de maletas (saturada)
    // bit  61     : enrutada
    // bit  62     : cumple SLA
    // bit  63     : entregada ya contada en maletasEntregadas
    private static final int  BITS_ARRIBO   = 31;
    private static final long MASCARA_ARRIBO   = (1L << BITS_ARRIBO) - 1;
    private static final long SIN_ARRIBO       = MASCARA_ARRIBO;
    private static final int  BITS_CANTIDAD = 30;
    private static final long MASCARA_CANTIDAD = (1L << BITS_CANTIDAD) - 1;
    private static final long BIT_ENRUTADA     = 1L << 61;
    private static final long BIT_CUMPLE_SLA   = 1L << 62;
    private static final long BIT_ENTREGADA    = 1L << 63;

    private static long empacar(int cantidad, boolean enrutada, boolean cumpleSla, long arriboMin) {
        long cant = Math.min(Math.max(cantidad, 0), MASCARA_CANTIDAD);
        long arribo = (arriboMin < 0 || arriboMin >= SIN_ARRIBO) ? SIN_ARRIBO : arriboMin;
        return arribo
                | (cant << BITS_ARRIBO)
                | (enrutada ? BIT_ENRUTADA : 0L)
                | (cumpleSla ? BIT_CUMPLE_SLA : 0L);
    }

    private static long arriboDe(long estado)    { return estado & MASCARA_ARRIBO; }
    private static long cantidadDe(long estado)  { return (estado >>> BITS_ARRIBO) & MASCARA_CANTIDAD; }
    private static boolean enrutadaDe(long e)    { return (e & BIT_ENRUTADA) != 0L; }
    private static boolean cumpleSlaDe(long e)   { return (e & BIT_CUMPLE_SLA) != 0L; }
    private static boolean entregadaDe(long e)   { return (e & BIT_ENTREGADA) != 0L; }

    void registrar(LoteEnvio b) {
        String key = claveLoteAuditoria(b);
        if (completos != null) { completos.put(key, b); return; }

        boolean enrutada = b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty();
        long arribo = ultimoArriboMin(b);
        long estado = empacar(b.getCantidad(), enrutada, b.isCumpleSLA(), arribo);

        Long previo = resumen.put(key, estado);
        if (previo != null) descontar(previo);
        maletas += cantidadDe(estado);
        if (enrutada) {
            enrutadas++;
            maletasEnrutadas += cantidadDe(estado);
            if (b.isCumpleSLA()) cumpleSla++;
            if (arriboDe(estado) != SIN_ARRIBO) {
                pendientesEntrega.add(new ArriboPendiente(arriboDe(estado), key));
            }
        }

        long ready = aMinutoEpoch(b.getTiempoListo());
        if (ready > corteMin) corteMin = ready;

        if (enrutada) sinRuta.remove(key);
        else sinRuta.put(key, b.clonar());
    }

    /** Deshace la contribución del estado anterior de un envío que se vuelve a registrar. */
    private void descontar(long previo) {
        maletas -= cantidadDe(previo);
        if (!enrutadaDe(previo)) return;
        enrutadas--;
        maletasEnrutadas -= cantidadDe(previo);
        if (cumpleSlaDe(previo)) cumpleSla--;
        if (entregadaDe(previo)) maletasEntregadas -= cantidadDe(previo);
    }

    Collection<LoteEnvio> sinRuta()   { return sinRuta.values(); }
    int sinRutaSize()                    { return sinRuta.size(); }
    Collection<LoteEnvio> completos() { return completos != null ? completos.values() : List.of(); }

    TotalesUnicos totalesUnicos() {
        if (resumen.isEmpty()) return new TotalesUnicos(0, 0, 0, 0, 0, 0L);
        int envios = resumen.size();
        int tardadas = enrutadas - cumpleSla;
        int sinRutaN = envios - enrutadas;
        return new TotalesUnicos(envios, enrutadas, sinRutaN, cumpleSla, tardadas, maletas);
    }

    void llenarAcumuladosFisicos(BloqueSimulacion bloque) {
        if (bloque == null || resumen.isEmpty()) return;
        graduarEntregas();
        bloque.setMaletasProcesadasAcum(maletas);
        bloque.setMaletasEnrutadasAcum(maletasEnrutadas);
        bloque.setMaletasEntregadasAcum(maletasEntregadas);
    }

    /**
     * Cuenta como entregadas las maletas cuyo último arribo ya quedó detrás del corte temporal
     * ({@code arribo <= corteMin}). Como {@code corteMin} solo crece, una entrega graduada solo
     * se revierte si el envío se re-registra ({@link #descontar}); las entradas obsoletas de la
     * cola se descartan al compararlas con el estado vigente.
     */
    private void graduarEntregas() {
        while (!pendientesEntrega.isEmpty() && pendientesEntrega.peek().arriboMin <= corteMin) {
            ArriboPendiente p = pendientesEntrega.poll();
            Long vigente = resumen.get(p.clave);
            if (vigente == null) continue;
            long estado = vigente;
            if (!enrutadaDe(estado) || entregadaDe(estado) || arriboDe(estado) != p.arriboMin) continue;
            resumen.put(p.clave, estado | BIT_ENTREGADA);
            maletasEntregadas += cantidadDe(estado);
        }
    }

    static long ultimoArriboMin(LoteEnvio b) {
        if (b.getRutaAsignada() == null || b.getSalidasAsignadas() == null) {
            return Long.MAX_VALUE;
        }
        int lastIdx = Math.min(b.getRutaAsignada().size(), b.getSalidasAsignadas().size()) - 1;
        if (lastIdx < 0) return Long.MAX_VALUE;
        return b.getSalidasAsignadas().get(lastIdx)
                + b.getRutaAsignada().get(lastIdx).duracionMinutos;
    }

    static long aMinutoEpoch(LocalDateTime dt) {
        if (dt == null) return Long.MIN_VALUE;
        return dt.toLocalDate().toEpochDay() * 1440L + dt.getHour() * 60L + dt.getMinute();
    }

    private static String claveLoteAuditoria(LoteEnvio b) {
        if (b == null) return "";
        return FormatoSimulacion.safe(b.getId());
    }
}
