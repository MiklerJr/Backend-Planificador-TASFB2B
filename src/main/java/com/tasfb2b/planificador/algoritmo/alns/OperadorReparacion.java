package com.tasfb2b.planificador.algoritmo.alns;


import java.util.List;
import java.util.Map;

public interface OperadorReparacion {

    void reparar(SolucionAlns solution, List<LoteEnvio> unassigned,
                Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport);
}
