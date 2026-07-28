package com.tasfb2b.planificador.algoritmo.alns;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;

import java.util.List;

public final class RutaCandidata {
    final List<Arista> aristas;
    final List<Long> salidasReales;
    final boolean cumpleSLA;
    final long arrivalMin;
    final long transitMin;
    final long slackMin;
    final double pressure;
    final double costoEscasez;
    private String cacheFirma;

    RutaCandidata(List<Arista> edges,
                  List<Long> salidasReales,
                  boolean cumpleSLA,
                  long arrivalMin,
                  long transitMin,
                  long slackMin,
                  double pressure,
                  double costoEscasez) {
        this.aristas = List.copyOf(edges);
        this.salidasReales = List.copyOf(salidasReales);
        this.cumpleSLA = cumpleSLA;
        this.arrivalMin = arrivalMin;
        this.transitMin = transitMin;
        this.slackMin = slackMin;
        this.pressure = pressure;
        this.costoEscasez = costoEscasez;
    }

    public List<Arista> getAristas() { return aristas; }
    public List<Long> getSalidasReales() { return salidasReales; }
    public boolean isCumpleSLA() { return cumpleSLA; }
    public long getLlegadaMin() { return arrivalMin; }
    public long getTransitoMin() { return transitMin; }
    public long getHolguraMin() { return slackMin; }
    public double getPresion() { return pressure; }
    public double getCostoEscasez() { return costoEscasez; }
    public int getTramos() { return aristas.size(); }

    public String signature() {
        String cached = cacheFirma;
        if (cached != null) return cached;
        StringBuilder sb = new StringBuilder(aristas.size() * 12);
        for (int i = 0; i < aristas.size(); i++) {
            sb.append(aristas.get(i).indice).append('@').append(salidasReales.get(i)).append(';');
        }
        cached = sb.toString();
        cacheFirma = cached;
        return cached;
    }
}
