package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.dto.AsignacionMaleta;
import com.tasfb2b.planificador.dto.EnvioEstadoResponse;
import com.tasfb2b.planificador.dto.TramoRuta;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Deriva el estado de un envío "en ruta" (y la clasificación de cada tramo) comparando los tiempos
 * UTC de su ruta contra un instante de referencia. Lógica pura: sin BD ni Spring, para poder
 * testearla directa. La usa {@code PlanificadorService.buscarEstadoEnvio}.
 *
 * <p>Todos los tiempos (tramos y {@code ahoraUtc}) están en el mismo eje UTC sin offset que produce
 * {@code AlgorithmMapper}/{@code buildAsignaciones}, así que la comparación es directa.
 */
public final class EnvioEstadoCalculator {

    public static final String COMPLETADO = "COMPLETADO";
    public static final String EN_CURSO   = "EN_CURSO";
    public static final String PENDIENTE  = "PENDIENTE";

    public static final String E_PROGRAMADO  = "PROGRAMADO";
    public static final String E_EN_VUELO    = "EN_VUELO";
    public static final String E_EN_ESCALA   = "EN_ESCALA";
    public static final String E_ENTREGADO   = "ENTREGADO";
    public static final String E_DESCONOCIDO = "DESCONOCIDO";

    private EnvioEstadoCalculator() {}

    /**
     * Clasifica los tramos de {@code asig} (mutando su {@code estado}) y devuelve el estado global.
     * Si {@code ahoraUtc} es null (no se pudo determinar el instante actual), deja los tramos sin
     * clasificar y reporta {@code DESCONOCIDO}.
     */
    public static EnvioEstadoResponse calcular(AsignacionMaleta asig, LocalDateTime ahoraUtc) {
        EnvioEstadoResponse r = new EnvioEstadoResponse();
        r.setAsignacion(asig);
        r.setInstanteReferencia(ahoraUtc != null ? ahoraUtc.toString() : null);

        List<TramoRuta> tramos = asig != null ? asig.getTramos() : null;
        int total = tramos != null ? tramos.size() : 0;
        r.setTramosTotales(total);

        if (total == 0) {
            // Envío sin tramos (no debería pasar para un enrutado): nada que clasificar.
            r.setEstado(ahoraUtc == null ? E_DESCONOCIDO : E_PROGRAMADO);
            return r;
        }

        r.setLlegadaFinalUtc(tramos.get(total - 1).getLlegadaUtc());

        if (ahoraUtc == null) {
            r.setEstado(E_DESCONOCIDO);
            return r;
        }

        int completados = 0;
        Integer enCursoIdx = null;
        int ultimoCompletadoIdx = -1;

        for (int i = 0; i < total; i++) {
            TramoRuta t = tramos.get(i);
            LocalDateTime salida = parse(t.getSalidaUtc());
            LocalDateTime llegada = parse(t.getLlegadaUtc());
            if (salida == null || llegada == null) {
                continue;   // tramo sin tiempos parseables: se deja sin estado
            }
            if (!llegada.isAfter(ahoraUtc)) {                 // llegada <= ahora
                t.setEstado(COMPLETADO);
                completados++;
                ultimoCompletadoIdx = i;
            } else if (!salida.isAfter(ahoraUtc)) {           // salida <= ahora < llegada
                t.setEstado(EN_CURSO);
                if (enCursoIdx == null) enCursoIdx = i;
            } else {                                          // salida > ahora
                t.setEstado(PENDIENTE);
            }
        }

        r.setTramosCompletados(completados);
        r.setTramoActualIdx(enCursoIdx);

        LocalDateTime salida0 = parse(tramos.get(0).getSalidaUtc());
        LocalDateTime llegadaUlt = parse(tramos.get(total - 1).getLlegadaUtc());

        if (enCursoIdx != null) {
            r.setEstado(E_EN_VUELO);                          // en el aire: ubicación = null
        } else if (salida0 != null && ahoraUtc.isBefore(salida0)) {
            r.setEstado(E_PROGRAMADO);
            r.setUbicacionActual(tramos.get(0).getOrigen()); // esperando en origen
        } else if (llegadaUlt != null && !ahoraUtc.isBefore(llegadaUlt)) {
            r.setEstado(E_ENTREGADO);
            r.setUbicacionActual(tramos.get(total - 1).getDestino());
        } else {
            r.setEstado(E_EN_ESCALA);                         // entre tramos, en una escala
            if (ultimoCompletadoIdx >= 0) {
                r.setUbicacionActual(tramos.get(ultimoCompletadoIdx).getDestino());
            }
        }
        return r;
    }

    /** Parseo tolerante de un ISO local datetime (con o sin segundos); null si no se puede. */
    private static LocalDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDateTime.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
