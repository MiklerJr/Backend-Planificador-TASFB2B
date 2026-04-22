package com.tasfb2b.planificador.algorithm.alns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.aco.Graph;

public class AlnsSolution {

    private List<LuggageBatch> batches;

    public AlnsSolution(List<LuggageBatch> batches) {
        this.batches = batches;
    }

    public double calculateCost() {
        double totalCost = 0.0;
        for (LuggageBatch batch : batches) {
            double transitTime = batch.getTotalTransitTimeMins();
            totalCost += transitTime;

            // Penalización extra si supera el SLA (24h o 48h)
            if (transitTime > batch.getSlaLimitHours() * 60) {
                totalCost += 5000.0; // Gran penalización por incumplir contrato
            }
        }
        return totalCost;
    }

    public boolean isValid(Graph graph) {
        Map<String, Integer> flightUsage = new HashMap<>();
        Map<String, Integer> airportUsage = new HashMap<>();

        for (LuggageBatch batch : batches) {
            for (Edge flight : batch.getAssignedRoute()) {
                // 1. Validar Capacidad de Avión (Arista)
                flightUsage.put(flight.id, flightUsage.getOrDefault(flight.id, 0) + batch.getQuantity());
                if (flightUsage.get(flight.id) > flight.capacity) return false;

                // 2. Validar Capacidad de Aeropuerto (Nodo)
                // Se asume que el aeropuerto tiene un límite (ej. 500-800 según tus requerimientos)
                String airportCode = flight.to.code;
                airportUsage.put(airportCode, airportUsage.getOrDefault(airportCode, 0) + batch.getQuantity());

                // Límite genérico de ejemplo (puedes ajustarlo por aeropuerto)
                if (airportUsage.get(airportCode) > 800) return false;
            }
        }
        return true;
    }

    public AlnsSolution cloneSolution() {
        List<LuggageBatch> clonedBatches = new ArrayList<>();
        for (LuggageBatch b : this.batches) {
            clonedBatches.add(b.cloneBatch());
        }
        return new AlnsSolution(clonedBatches);
    }

    public List<LuggageBatch> getBatches() {
        return batches;
    }
}