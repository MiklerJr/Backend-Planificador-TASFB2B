package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

final class Decision {
    final LoteEnvio lote;
    final RutaCandidata ruta;
    final String clave;
    final String claveLote;
    final double heuristica;

    Decision(LoteEnvio lote, RutaCandidata ruta, String clave, String claveLote, double heuristica) {
        this.lote = lote;
        this.ruta = ruta;
        this.clave = clave;
        this.claveLote = claveLote;
        this.heuristica = heuristica;
    }
}
