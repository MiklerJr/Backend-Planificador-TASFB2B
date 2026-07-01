package com.tasfb2b.planificador.dto.simulacion;

import lombok.Data;
import java.util.List;
import java.util.Map;

import com.tasfb2b.planificador.dto.dataset.AeropuertoDTO;
import com.tasfb2b.planificador.dto.vuelos.VueloBackend;

/**
 * Wrapper raíz de la respuesta de una simulación. Agrupa los read models del paquete
 * {@code com.tasfb2b.planificador.dto} ({@link Metricas}, {@link BloqueSimulacion},
 * {@link AsignacionMaleta}, {@link TramoRuta}, {@link CargaVuelo}, {@link OcupacionAlmacen},
 * {@link OcupacionAlmacenSlot}, {@link AlertaAlmacen}, {@link VueloBackend}, {@link AeropuertoDTO}).
 * El JSON expone los nombres y la forma de campo del contrato del front.
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
