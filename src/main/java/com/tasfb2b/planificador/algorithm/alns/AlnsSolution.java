package com.tasfb2b.planificador.algorithm.alns;

import java.util.ArrayList;
import java.util.List;

public class AlnsSolution {

    private List<LuggageBatch> batches;

    // Pesos de la función objetivo. Defaults coinciden con los valores hardcoded heredados.
    // Se sobreescriben con setPesos() desde AlgorithmALNS cuando hay PlanificadorProperties.
    private double pesoTransit    = 1.0;
    private double pesoTarde      = 5000.0;
    private double pesoUsoAlmacen = 0.0;   // 0 = desactivado (compat fase 1-4)

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

    /** Permite ajustar los pesos después de la construcción (preserva pesos al clonar). */
    public void setPesos(double pesoTransit, double pesoTarde) {
        this.pesoTransit = pesoTransit;
        this.pesoTarde   = pesoTarde;
    }

    public void setPesoUsoAlmacen(double pesoUsoAlmacen) {
        this.pesoUsoAlmacen = pesoUsoAlmacen;
    }

    /**
     * Función objetivo:
     * <pre>
     *   cost = pesoTransit * Σ transitTime(b)
     *        + pesoTarde   * #(batches con SLA incumplido)
     *        + pesoUsoAlmacen * Σ (escalas(b) * cantidad(b))
     * </pre>
     * El término de uso de almacén penaliza escalas: cada escala intermedia
     * ocupa almacén y consume capacidad — proxy lineal del riesgo de colapso.
     * Se desactiva con {@code pesoUsoAlmacen=0}.
     */
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
            // Una ruta directa (1 segmento) tiene 0 escalas → 0 penalización.
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