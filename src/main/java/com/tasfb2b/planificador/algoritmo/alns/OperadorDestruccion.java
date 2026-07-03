package com.tasfb2b.planificador.algoritmo.alns;

import java.util.List;
import java.util.Random;

public interface OperadorDestruccion {
    List<LoteEnvio> destroy(SolucionAlns solution, double factor);

    default void setRandom(Random rng) {}
}