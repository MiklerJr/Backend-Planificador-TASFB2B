package com.tasfb2b.planificador.dto.trabajos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertaColapso {

    public static final String VERDE = "VERDE";
    public static final String AMBAR = "AMBAR";
    public static final String ROJO  = "ROJO";

    private String nivel;
    private String mensaje;
    private int bloque;
    private double utilAlmacenMax;
    private String almacenCritico;
    private double holguraSlaMin;
    private String envioUrgente;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String causaDominante;

    public static AlertaColapso verde() {
        return new AlertaColapso(VERDE, "Sin riesgo de colapso", 0, 0.0, null, 1.0, null, null);
    }
}
