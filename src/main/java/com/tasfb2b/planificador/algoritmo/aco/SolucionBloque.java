package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.RutaCandidata;

import java.util.List;

final class SolucionBloque {
    final List<Asignacion> asignaciones;
    final int enrutados;
    final int cumpleSla;
    final int tardados;
    final int sinRuta;
    final double costo;

    SolucionBloque(List<Asignacion> asignaciones, int totalBatches) {
        this.asignaciones = List.copyOf(asignaciones);
        this.enrutados = asignaciones.size();
        int ok = 0;
        double totalCost = 0.0;
        for (Asignacion a : asignaciones) {
            RutaCandidata r = a.ruta;
            if (r.isCumpleSLA()) ok++;
            long late = Math.max(0L, -r.getHolguraMin());
            totalCost += r.getTransitoMin()
                    + late * 10_000.0
                    + r.getPresion() * 500.0
                    + Math.max(0, r.getTramos() - 1) * 30.0;
        }
        this.cumpleSla = ok;
        this.tardados = enrutados - ok;
        this.sinRuta = totalBatches - enrutados;
        this.costo = totalCost + sinRuta * 1_000_000.0;
    }
}
