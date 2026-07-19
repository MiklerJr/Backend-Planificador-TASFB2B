package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.dto.simulacion.BloqueSimulacion;
import com.tasfb2b.planificador.utilidades.FormatoSimulacion;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acumulador de auditoría por corrida (Fase 5b): registra el último estado conocido de cada
 * envío ÚNICO (deduplicado por clave de lote) para las métricas globales y los sin-ruta que
 * alimenta la auditoría diferida ({@code EstadoJob.auditoriaSinRuta}). En modo
 * {@code retenerBatches} conserva los lotes completos (warm-up / estado inicial).
 */
final class AcumuladorAuditoria {
    private final Map<String, ResumenEnvio> resumen = new LinkedHashMap<>();
    private final Map<String, LoteEnvio> sinRuta = new LinkedHashMap<>();
    private final Map<String, LoteEnvio> completos;

    AcumuladorAuditoria(boolean retenerBatches) {
        this.completos = retenerBatches ? new LinkedHashMap<>() : null;
    }

    private record ResumenEnvio(long quantity, boolean enrutada, boolean cumpleSLA,
                                long readyMin, long ultimoArriboMin) {
        static ResumenEnvio de(LoteEnvio b) {
            boolean enrutada = b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty();
            return new ResumenEnvio(b.getCantidad(), enrutada, b.isCumpleSLA(),
                    AcumuladorAuditoria.aMinutoEpoch(b.getTiempoListo()),
                    AcumuladorAuditoria.ultimoArriboMin(b));
        }
    }

    void registrar(LoteEnvio b) {
        String key = claveLoteAuditoria(b);
        if (completos != null) { completos.put(key, b); return; }
        resumen.put(key, ResumenEnvio.de(b));
        boolean enrutada = b.getRutaAsignada() != null && !b.getRutaAsignada().isEmpty();
        if (enrutada) sinRuta.remove(key);
        else sinRuta.put(key, b.clonar());
    }

    boolean isEmpty()                    { return resumen.isEmpty(); }
    Collection<LoteEnvio> sinRuta()   { return sinRuta.values(); }
    int sinRutaSize()                    { return sinRuta.size(); }
    Collection<LoteEnvio> completos() { return completos != null ? completos.values() : List.of(); }

    TotalesUnicos totalesUnicos() {
        if (resumen.isEmpty()) return new TotalesUnicos(0, 0, 0, 0, 0, 0L);
        int envios = resumen.size();
        int enrutadas = 0, cumpleSLA = 0;
        long maletas = 0L;
        for (ResumenEnvio r : resumen.values()) {
            maletas += r.quantity();
            if (r.enrutada()) { enrutadas++; if (r.cumpleSLA()) cumpleSLA++; }
        }
        int tardadas = enrutadas - cumpleSLA;
        int sinRutaN = envios - enrutadas;
        return new TotalesUnicos(envios, enrutadas, sinRutaN, cumpleSLA, tardadas, maletas);
    }

    void llenarAcumuladosFisicos(BloqueSimulacion bloque) {
        if (bloque == null || resumen.isEmpty()) return;
        long corteMin = Long.MIN_VALUE;
        for (ResumenEnvio r : resumen.values()) if (r.readyMin() > corteMin) corteMin = r.readyMin();

        long procesadas = 0L, enrutadas = 0L, entregadas = 0L;
        for (ResumenEnvio r : resumen.values()) {
            procesadas += r.quantity();
            if (!r.enrutada()) continue;
            enrutadas += r.quantity();
            if (r.ultimoArriboMin() <= corteMin) entregadas += r.quantity();
        }
        bloque.setMaletasProcesadasAcum(procesadas);
        bloque.setMaletasEnrutadasAcum(enrutadas);
        bloque.setMaletasEntregadasAcum(entregadas);
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
        return String.join("|",
                FormatoSimulacion.safe(b.getId()),
                FormatoSimulacion.safe(b.getCodigoOrigen()),
                FormatoSimulacion.safe(b.getCodigoDestino()),
                b.getTiempoListo() != null ? b.getTiempoListo().toString() : "",
                String.valueOf(b.getCantidad()));
    }
}
