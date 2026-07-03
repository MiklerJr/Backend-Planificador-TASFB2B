package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;

import java.util.List;

final class SolucionBloque {
    final List<Asignacion> asignaciones;
    final int enrutados;
    final int cumpleSla;
    final int tardados;
    final int sinRuta;
    final double cost;

    SolucionBloque(List<Asignacion> asignaciones, int totalBatches) {
        this.asignaciones = List.copyOf(asignaciones);
        this.enrutados = asignaciones.size();
        int ok = 0;
        double totalCost = 0.0;
        for (Asignacion a : asignaciones) {
            RutaCandidata r = a.route;
            if (r.isCumpleSLA()) ok++;
            long late = Math.max(0L, -r.getSlackMin());
            totalCost += r.getTransitMin()
                    + late * 10_000.0
                    + r.getPressure() * 500.0
                    + Math.max(0, r.getLegs() - 1) * 30.0;
        }
        this.cumpleSla = ok;
        this.tardados = enrutados - ok;
        this.sinRuta = totalBatches - enrutados;
        this.cost = totalCost + sinRuta * 1_000_000.0;
    }
}
