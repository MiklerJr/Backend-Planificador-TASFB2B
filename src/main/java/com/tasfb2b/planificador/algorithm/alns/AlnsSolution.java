package com.tasfb2b.planificador.algorithm.alns;

import java.util.ArrayList;
import java.util.List;

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