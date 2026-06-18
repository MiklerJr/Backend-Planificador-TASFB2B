package com.tasfb2b.planificador.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Wrapper raíz de la respuesta de una simulación. Las clases que antes vivían anidadas aquí
 * ({@link Metricas}, {@link BloqueSimulacion}, {@link AsignacionMaleta}, {@link TramoRuta},
 * {@link CargaVuelo}, {@link OcupacionAlmacen}, {@link OcupacionAlmacenSlot},
 * {@link AlertaAlmacen}, {@link VueloBackend}, {@link AeropuertoDTO}) se extrajeron a archivos
 * propios del mismo paquete {@code com.tasfb2b.planificador.dto} (Tanda 1A de modularización).
 * El contrato JSON serializado no cambia: nombres y forma de los campos son idénticos.
 */
@Data
public class SimulacionResponse {

    private Metricas metricas;
    private int totalBloques;
    private List<VueloBackend> vuelosPlaneados;
    private Map<String, AeropuertoDTO> aeropuertosInfo;

    // Parámetros de simulación para el frontend
    // Sc = k * saMinutos → cuántos minutos de datos consume el frontend por tick visual
    private int k;           // factor de aceleración (K=1 día-a-día, K=14 sim-3días, K=144 colapso)
    private int saMinutos;   // tamaño de ventana del planificador (Sa)
}
