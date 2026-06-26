package com.tasfb2b.planificador.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alerta de colapso logístico INMINENTE (pre-colapso). A diferencia del colapso real (que detiene
 * la simulación), esta alerta solo informa: se loguea en consola y se expone al front vía
 * {@code GET /jobs/{id}/alerta-colapso} y dentro de {@code /jobs/{id}/estado}.
 *
 * <p>Mide la cercanía a los DOS criterios de colapso reales:
 * <ul>
 *   <li><b>Almacén cerca de su capacidad</b> ({@code utilAlmacenMax} en {@code almacenCritico}).</li>
 *   <li><b>Backlog cerca de su SLA</b> ({@code holguraSlaMin} = fracción de SLA restante del envío
 *       más urgente, {@code envioUrgente}).</li>
 * </ul>
 * El {@code nivel} es el máximo entre ambas señales: VERDE (sin riesgo), AMBAR (acercándose),
 * ROJO (a punto de colapsar).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertaColapso {

    public static final String VERDE = "VERDE";
    public static final String AMBAR = "AMBAR";
    public static final String ROJO  = "ROJO";

    /** Nivel de riesgo: VERDE | AMBAR | ROJO. */
    private String nivel;
    /** Mensaje legible para mostrar al usuario / consola. */
    private String mensaje;
    /** Índice del bloque al que corresponde la alerta. */
    private int bloque;
    /** Utilización pico de almacén observada (0..1+). */
    private double utilAlmacenMax;
    /** Aeropuerto con la utilización pico de almacén. */
    private String almacenCritico;
    /** Holgura SLA mínima del backlog (fracción de SLA restante, 0..1; 1.0 si no hay backlog). */
    private double holguraSlaMin;
    /** Envío del backlog más cercano a vencer su SLA. */
    private String envioUrgente;

    /**
     * Señal que domina el {@code nivel}: {@code "almacen"} (almacén cerca de capacidad),
     * {@code "sla"} (backlog cerca de vencer), {@code "ambos"} (las dos activas) o {@code null}
     * (VERDE, sin riesgo). Permite al front saber el <b>cómo</b> sin parsear {@code mensaje}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String causaDominante;

    /** Alerta sin riesgo (estado por defecto). */
    public static AlertaColapso verde() {
        return new AlertaColapso(VERDE, "Sin riesgo de colapso", 0, 0.0, null, 1.0, null, null);
    }
}
