package com.tasfb2b.planificador.algoritmo.aco;

public class ConstantesOperativas {

    public static double UMBRAL_VERDE = 0.70;
    public static double UMBRAL_AMBAR = 0.90;
    // TIEMPO_MIN_ESCALA se movió a config yaml: planificador.operativo.tiempo-min-escala-minutos
    // (PlanificadorProperties.Operativo), leído por OperadorReparacionVoraz y AuditoriaService.
}
