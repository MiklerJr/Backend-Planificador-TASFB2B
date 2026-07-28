package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

final class Asignacion {
    final LoteEnvio lote;
    final RutaCandidata ruta;
    final String clave;
    final String claveLote;

    Asignacion(LoteEnvio lote, RutaCandidata ruta, String clave, String claveLote) {
        this.lote = lote;
        this.ruta = ruta;
        this.clave = clave;
        this.claveLote = claveLote;
    }
}
