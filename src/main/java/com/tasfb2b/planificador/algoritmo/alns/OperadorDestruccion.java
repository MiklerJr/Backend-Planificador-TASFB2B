package com.tasfb2b.planificador.algoritmo.alns;

import java.util.List;
import java.util.Random;

public interface OperadorDestruccion {
    List<LoteEnvio> destruir(SolucionAlns solution, double factor);

    default void setAleatorio(Random rng) {}
}