package com.tasfb2b.planificador.algorithm.alns;

import java.util.ArrayList;
import java.util.List;

public class AlnsSolution {

    private List<LuggageBatch> batches;

    private double pesoTransit    = 1.0;
    private double pesoTarde      = 5000.0;
    private double pesoUsoAlmacen = 0.0;   // 0 = desactivado

    public AlnsSolution(List<LuggageBatch> batches) {
        this.batches = batches;
    }

    public AlnsSolution(List<LuggageBatch> batches, double pesoTransit, double pesoTarde) {
        this.batches      = batches;
        this.pesoTransit  = pesoTransit;
        this.pesoTarde    = pesoTarde;
    }

    public AlnsSolution(List<LuggageBatch> batches,
                        double pesoTransit, double pesoTarde, double pesoUsoAlmacen) {
        this.batches        = batches;
        this.pesoTransit    = pesoTransit;
        this.pesoTarde      = pesoTarde;
        this.pesoUsoAlmacen = pesoUsoAlmacen;
    }

    public void setPesos(double pesoTransit, double pesoTarde) {
        this.pesoTransit = pesoTransit;
        this.pesoTarde   = pesoTarde;
    }

    public void setPesoUsoAlmacen(double pesoUsoAlmacen) {
        this.pesoUsoAlmacen = pesoUsoAlmacen;
    }

    public double calculateCost() {
        double totalCost = 0.0;
        for (LuggageBatch batch : batches) {
            double transitTime = batch.getTotalTransitTimeMins();
            totalCost += pesoTransit * transitTime;

            // Penalización fija si supera el SLA (24h o 48h).
            if (transitTime > batch.getSlaLimitHours() * 60) {
                totalCost += pesoTarde;
            }

            // Penalización por uso de almacén: # escalas intermedias × cantidad.
            if (pesoUsoAlmacen > 0 && batch.getAssignedRoute() != null
                    && batch.getAssignedRoute().size() > 1) {
                int escalas = batch.getAssignedRoute().size() - 1;
                totalCost += pesoUsoAlmacen * escalas * batch.getQuantity();
            }
        }
        return totalCost;
    }

    public AlnsSolution cloneSolution() {
        List<LuggageBatch> clonedBatches = new ArrayList<>();
        for (LuggageBatch b : this.batches) {
            clonedBatches.add(b.cloneBatch());
        }
        AlnsSolution clon = new AlnsSolution(clonedBatches, pesoTransit, pesoTarde, pesoUsoAlmacen);
        return clon;
    }

    public List<LuggageBatch> getBatches() {
        return batches;
    }
}