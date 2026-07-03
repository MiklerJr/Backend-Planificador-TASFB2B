package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.algorithm.aco.ConstantesOperativas;
import com.tasfb2b.planificador.dto.vuelos.CargaVuelo;
import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacen;
import com.tasfb2b.planificador.model.dataset.Vuelo;

public final class SimulacionFormat {

    private SimulacionFormat() {}

    public static String safe(String value) {
        return value != null ? value : "";
    }

    public static double porcentaje(long valor, long total) {
        if (total <= 0) return 0.0;
        return Math.round((valor * 10000.0) / total) / 100.0;
    }

    public static String semaforoPorPorcentaje(double porcentaje) {
        double ratio = porcentaje / 100.0;
        if (ratio <= ConstantesOperativas.UMBRAL_VERDE) return "VERDE";
        if (ratio <= ConstantesOperativas.UMBRAL_AMBAR) return "AMBAR";
        return "ROJO";
    }

    public static void completarCargaVuelo(CargaVuelo dto) {
        double porcentaje = porcentaje(dto.getCargaAsignada(), dto.getCapacidadMaxima());
        dto.setPorcentajeCarga(porcentaje);
        dto.setSemaforo(semaforoPorPorcentaje(porcentaje));
    }

    public static void completarOcupacionAlmacen(OcupacionAlmacen dto) {
        double porcentaje = porcentaje(dto.getOcupacionAsignada(), dto.getCapacidadMaxima());
        dto.setPorcentajeOcupacion(porcentaje);
        dto.setSemaforo(semaforoPorPorcentaje(porcentaje));
    }

    public static String vueloFrontId(Vuelo v) {
        if (v == null) return "";
        if (v.getId() != null) return v.getId().toString();
        String origen = v.getAeropuertoOrigen() != null ? v.getAeropuertoOrigen().getCodigo() : safe(v.getOrigen());
        String destino = v.getAeropuertoDestino() != null ? v.getAeropuertoDestino().getCodigo() : safe(v.getDestino());
        String salida = v.getFechaHoraSalida() != null ? v.getFechaHoraSalida().toLocalTime().toString() : "";
        return origen + "-" + destino + "-" + salida;
    }
}
