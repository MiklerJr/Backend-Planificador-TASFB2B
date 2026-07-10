package com.tasfb2b.planificador.utilidades;

import com.tasfb2b.planificador.dto.simulacion.AsignacionMaleta;
import com.tasfb2b.planificador.dto.simulacion.EnvioEstadoResponse;
import com.tasfb2b.planificador.dto.simulacion.TramoRuta;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class CalculadorEstadoEnvio {

    public static final String COMPLETADO = "COMPLETADO";
    public static final String EN_CURSO   = "EN_CURSO";
    public static final String PENDIENTE  = "PENDIENTE";

    public static final String E_PROGRAMADO  = "PROGRAMADO";
    public static final String E_EN_VUELO    = "EN_VUELO";
    public static final String E_EN_ESCALA   = "EN_ESCALA";
    public static final String E_ENTREGADO   = "ENTREGADO";
    public static final String E_DESCONOCIDO = "DESCONOCIDO";

    private CalculadorEstadoEnvio() {}

    public static EnvioEstadoResponse calcular(AsignacionMaleta asig, LocalDateTime ahoraUtc) {
        EnvioEstadoResponse r = new EnvioEstadoResponse();
        r.setAsignacion(asig);
        r.setInstanteReferencia(ahoraUtc != null ? ahoraUtc.toString() : null);

        List<TramoRuta> tramos = asig != null ? asig.getTramos() : null;
        int total = tramos != null ? tramos.size() : 0;
        r.setTramosTotales(total);

        if (total == 0) {
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
            String destinoFinal = asig != null ? asig.getDestino() : null;
            String destinoUltTramo = tramos.get(total - 1).getDestino();
            boolean llegaAlDestino = destinoFinal == null || destinoFinal.isBlank()
                    || destinoFinal.equals(destinoUltTramo);
            r.setEstado(llegaAlDestino ? E_ENTREGADO : E_EN_ESCALA);
            r.setUbicacionActual(destinoUltTramo);
        } else {
            r.setEstado(E_EN_ESCALA);                         // entre tramos, en una escala
            if (ultimoCompletadoIdx >= 0) {
                r.setUbicacionActual(tramos.get(ultimoCompletadoIdx).getDestino());
            }
        }
        return r;
    }

    /**
     * Estado agregado de un envío FRAGMENTADO a partir del estado de cada sub-lote. El estado global es
     * el del sub-lote MENOS avanzado (ENTREGADO sólo si todos lo están); {@code tramosTotales} y
     * {@code tramosCompletados} son sumas; {@code llegadaFinalUtc} es el máximo. La {@code asignacion}
     * global va null (cada fragmento lleva la suya en {@code fragmentos}).
     */
    public static EnvioEstadoResponse agregarFragmentos(List<AsignacionMaleta> asigs, LocalDateTime ahoraUtc) {
        EnvioEstadoResponse agg = new EnvioEstadoResponse();
        agg.setInstanteReferencia(ahoraUtc != null ? ahoraUtc.toString() : null);

        List<EnvioEstadoResponse> fragmentos = new ArrayList<>(asigs.size());
        int tramosTotales = 0, tramosCompletados = 0;
        String llegadaMax = null;
        int rankMin = Integer.MAX_VALUE;
        String estadoMenosAvanzado = E_DESCONOCIDO;

        for (AsignacionMaleta a : asigs) {
            EnvioEstadoResponse f = calcular(a, ahoraUtc);
            fragmentos.add(f);
            tramosTotales += f.getTramosTotales();
            tramosCompletados += f.getTramosCompletados();
            llegadaMax = maxIso(llegadaMax, f.getLlegadaFinalUtc());
            int rank = rangoEstado(f.getEstado());
            if (rank < rankMin) {
                rankMin = rank;
                estadoMenosAvanzado = f.getEstado();
            }
        }

        agg.setFragmentos(fragmentos);
        agg.setTramosTotales(tramosTotales);
        agg.setTramosCompletados(tramosCompletados);
        agg.setLlegadaFinalUtc(llegadaMax);
        agg.setEstado(estadoMenosAvanzado);
        return agg;
    }

    /** Orden de avance de un envío (menor = menos avanzado): controla el estado agregado. */
    private static int rangoEstado(String estado) {
        if (estado == null) return 0;
        switch (estado) {
            case E_DESCONOCIDO: return 0;
            case E_PROGRAMADO:  return 1;
            case E_EN_ESCALA:   return 2;
            case E_EN_VUELO:    return 3;
            case E_ENTREGADO:   return 4;
            default:            return 1;
        }
    }

    private static String maxIso(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;   // ISO-8601 es comparable lexicográficamente
    }

    private static LocalDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDateTime.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
