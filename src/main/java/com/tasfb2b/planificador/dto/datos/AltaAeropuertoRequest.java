package com.tasfb2b.planificador.dto.datos;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AltaAeropuertoRequest {
    private String icao;
    private String ciudad;
    private Integer husoHorario;
    private int capacidad;
    private Double latitud;
    private Double longitud;
    private String continente;
}
