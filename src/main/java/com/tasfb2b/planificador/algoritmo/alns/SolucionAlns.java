package com.tasfb2b.planificador.algoritmo.alns;

import java.util.ArrayList;
import java.util.List;

public class SolucionAlns {

    private List<LoteEnvio> batches;

    private double pesoTransit    = 1.0;
    private double pesoTarde      = 5000.0;
    private double pesoUsoAlmacen = 0.0;   // 0 = desactivado

    public SolucionAlns(List<LoteEnvio> batches) {
        this.batches = batches;
    }

    public SolucionAlns(List<LoteEnvio> batches, double pesoTransit, double pesoTarde) {
        this.batches      = batches;
        this.pesoTransit  = pesoTransit;
        this.pesoTarde    = pesoTarde;
    }

    public SolucionAlns(List<LoteEnvio> batches,
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
        for (LoteEnvio batch : batches) {
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

    public SolucionAlns cloneSolution() {
        List<LoteEnvio> clonedBatches = new ArrayList<>();
        for (LoteEnvio b : this.batches) {
            clonedBatches.add(b.cloneBatch());
        }
        SolucionAlns clon = new SolucionAlns(clonedBatches, pesoTransit, pesoTarde, pesoUsoAlmacen);
        return clon;
    }

    public List<LoteEnvio> getBatches() {
        return batches;
    }
}