package com.tasfb2b.planificador.dto.dataset;

import lombok.Data;

@Data
public class AeropuertoDTO {
    private String codigo;
    private double latitud;
    private double longitud;
    /** Capacidad real de almacen del aeropuerto, en maletas individuales. */
    private Integer capacidadAlmacen;
    /**
     * Offset horario respecto a UTC (GMT), con signo, en horas. Es el MISMO valor que el motor usa
     * para normalizar a UTC ({@code Aeropuerto.offsetHorario}); el front lo usa para mostrar el reloj
     * local del registrador y para convertir local→UTC, de modo que front y back comparten un único
     * offset. El dataset solo trae husos enteros (p. ej. Delhi +5, Copenhague +2), por eso es un
     * {@code Double} sin fracción hoy; el tipo admite husos {@code :30}/{@code :45} si el dato fuente
     * los tuviera en el futuro.
     */
    private Double gmt;
}
