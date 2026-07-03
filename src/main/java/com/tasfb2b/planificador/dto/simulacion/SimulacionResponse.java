package com.tasfb2b.planificador.dto.simulacion;

import lombok.Data;
import java.util.List;
import java.util.Map;

import com.tasfb2b.planificador.dto.datos.AeropuertoDTO;
import com.tasfb2b.planificador.dto.vuelos.VueloBackend;


@Data
public class SimulacionResponse {

    private Metricas metricas;
    private int totalBloques;
    private List<VueloBackend> vuelosPlaneados;
    private Map<String, AeropuertoDTO> aeropuertosInfo;

    // Parámetros de simulación para el frontend
    private int k;           // factor de aceleración
    private int saMinutos;   // tamaño de ventana del planificador (Sa)
}
