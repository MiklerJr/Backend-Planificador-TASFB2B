package com.tasfb2b.planificador.algoritmo.alns;

import java.util.ArrayList;
import java.util.List;

public class SolucionAlns {

    private List<LoteEnvio> lotes;

    private double pesoTransit    = 1.0;
    private double pesoTarde      = 5000.0;
    private double pesoUsoAlmacen = 0.0;   // 0 = desactivado

    public SolucionAlns(List<LoteEnvio> lotes) {
        this.lotes = lotes;
    }

    public SolucionAlns(List<LoteEnvio> lotes, double pesoTransit, double pesoTarde) {
        this.lotes        = lotes;
        this.pesoTransit  = pesoTransit;
        this.pesoTarde    = pesoTarde;
    }

    public SolucionAlns(List<LoteEnvio> lotes,
                        double pesoTransit, double pesoTarde, double pesoUsoAlmacen) {
        this.lotes          = lotes;
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

    public double calcularCosto() {
        double totalCost = 0.0;
        for (LoteEnvio batch : lotes) {
            double transitTime = batch.getTiempoTransitoTotalMin();
            totalCost += pesoTransit * transitTime;

            if (transitTime > batch.getHorasLimiteSla() * 60) {
                totalCost += pesoTarde;
            }

            if (pesoUsoAlmacen > 0 && batch.getRutaAsignada() != null
                    && batch.getRutaAsignada().size() > 1) {
                int escalas = batch.getRutaAsignada().size() - 1;
                totalCost += pesoUsoAlmacen * escalas * batch.getCantidad();
            }
        }
        return totalCost;
    }

    public SolucionAlns clonar() {
        List<LoteEnvio> clonedBatches = new ArrayList<>();
        for (LoteEnvio b : this.lotes) {
            clonedBatches.add(b.clonar());
        }
        SolucionAlns clon = new SolucionAlns(clonedBatches, pesoTransit, pesoTarde, pesoUsoAlmacen);
        return clon;
    }

    public List<LoteEnvio> getLotes() {
        return lotes;
    }
}
