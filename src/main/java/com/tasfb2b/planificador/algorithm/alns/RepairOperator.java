package com.tasfb2b.planificador.algorithm.alns;

import java.util.List;
import java.util.Map;

public interface RepairOperator {

    void repair(AlnsSolution solution, List<LuggageBatch> unassigned,
                Map<Long, Integer> blockFlight, Map<Long, Integer> blockAirport);
}
