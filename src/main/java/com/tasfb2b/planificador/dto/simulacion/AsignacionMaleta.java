package com.tasfb2b.planificador.dto.simulacion;

import lombok.Data;
import java.util.List;

@Data
public class AsignacionMaleta {
    private String batchId;
    private String origen;
    private String destino;
    /** Numero de maletas individuales dentro del envio/lote. */
    private int cantidad;
    private boolean enrutada;
    private boolean cumpleSLA;
    private List<String> rutaVuelos;
    /** Tramos con tiempos reales UTC; permite al frontend rastrear dónde está la maleta. */
    private List<TramoRuta> tramos;
    /**
     * Nacimiento/registro del envío en hora local del aeropuerto de origen
     * (wall-clock, ISO sin offset). Es el instante desde el que las maletas
     * existen esperando en origen, antes de su primer vuelo.
     */
    private String registroLocal;
    /**
     * Mismo nacimiento expresado en UTC real (offset del origen aplicado).
     * Permite al front ubicar el envío en el eje de tiempo global desde que
     * nace, aunque su origen esté en otro huso.
     */
    private String registroUtc;
}
