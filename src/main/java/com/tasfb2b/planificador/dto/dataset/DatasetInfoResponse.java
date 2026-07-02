package com.tasfb2b.planificador.dto.dataset;

import lombok.Data;

@Data
public class DatasetInfoResponse {
    private String primeraVentana;
    private String ultimaVentana;
    private long diasDisponibles;
    private int totalMaletas;
    private int totalEnvios;
    private long totalMaletasIndividuales;
    private int totalAeropuertos;
    private int totalVuelos;
}
