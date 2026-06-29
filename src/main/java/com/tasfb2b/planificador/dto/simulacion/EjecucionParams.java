package com.tasfb2b.planificador.dto.simulacion;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Parámetros de una corrida del planificador. Permite configurar por petición
 * los valores que antes solo eran globales (en {@code application.yaml}):
 * {@code Sa}, {@code Ta} y la ventana temporal en días.
 *
 * <p>Cada campo numérico que sea {@code null} cae al default del yaml. Esto
 * permite que el cliente personalice solo lo que necesita por endpoint sin
 * tener que modificar configuración global.
 *
 * <p>Cálculo dinámico: {@code ventanasTotales = (dias · 24 · 60) / saMin},
 * sin acoplarse al {@code max-ventanas} estático.
 */
@Data
@NoArgsConstructor
public class EjecucionParams {
    /** Factor de aceleración K. Default 14 si null. */
    private Integer k;
    /** "alns" | "aco". Default "alns" si null. */
    private String motor;
    /** Seed reproducible. Default = aleatorio si null. */
    private Long seed;
    /** Fecha de inicio del subperíodo. Default = primera ventana del dataset si null. */
    private LocalDateTime fechaInicio;
    /** Tamaño de ventana del planificador (Sa) en minutos. Default = props.scenario.sa-minutos si null. */
    private Integer saMin;
    /** Presupuesto Ta por bloque en segundos. Default = props.scenario.ta-segundos si null (0 = sin Ta fijo). */
    private Integer taSegundos;
    /**
     * Duración total de la simulación en días. Si se especifica, sustituye al
     * {@code max-ventanas} global con el cálculo {@code (dias·24·60)/Sa}.
     * Default = props.scenario.max-ventanas si null.
     */
    private Integer dias;

    /** Para escenario 3: umbral de tasa sinRuta que dispara colapso. */
    private Double umbralColapso;

    /**
     * Escenario 2: si true y {@code fechaInicio} está por delante del inicio del dataset, hace un
     * <b>procesamiento previo (warm-up)</b> simulando {@code [inicio dataset, fechaInicio)} para
     * llegar a {@code fechaInicio} con backlog/ocupaciones realistas. Por <b>defecto desactivado</b>:
     * la simulación arranca directamente en {@code fechaInicio} sin procesar el período anterior.
     */
    private boolean procesamientoPrevio = false;
}
