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

final class AcumuladorAuditoria {
    private final Map<String, Long> resumen = new HashMap<>();
    private final Map<String, LoteEnvio> sinRuta = new LinkedHashMap<>();
    private final Map<String, LoteEnvio> completos;

    private int enrutadas;
    private int cumpleSla;
    private long maletas;
    private long maletasEnrutadas;
    private long maletasEntregadas;
    private long corteMin = Long.MIN_VALUE;

    private final PriorityQueue<ArriboPendiente> pendientesEntrega =
            new PriorityQueue<>((a, b) -> Long.compare(a.arriboMin, b.arriboMin));

    AcumuladorAuditoria(boolean retenerBatches) {
        this.completos = retenerBatches ? new LinkedHashMap<>() : null;
    }

    private record ArriboPendiente(long arriboMin, String clave) {}

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
