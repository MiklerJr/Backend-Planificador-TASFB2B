package com.tasfb2b.planificador.dto.datos;

import lombok.Data;

@Data
public class DatosInfoResponse {
    private String primeraVentana;
    private String ultimaVentana;
    private long diasDisponibles;
    private int totalMaletas;
    private int totalEnvios;
    private long totalMaletasIndividuales;
    private int totalAeropuertos;
    private int totalVuelos;
}
