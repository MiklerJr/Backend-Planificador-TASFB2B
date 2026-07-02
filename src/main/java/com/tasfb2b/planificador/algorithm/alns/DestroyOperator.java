package com.tasfb2b.planificador.algorithm.alns;

import java.util.List;
import java.util.Random;

public interface DestroyOperator {
    List<LuggageBatch> destroy(AlnsSolution solution, double factor);

    default void setRandom(Random rng) {}
}