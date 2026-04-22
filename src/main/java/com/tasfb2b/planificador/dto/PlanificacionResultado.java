package com.tasfb2b.planificador.dto;

import java.util.List;

public class PlanificacionResultado {

    public String idEnvio;
    public String origen;
    public String destino;
    public int cantidadMaletas;
    public List<String> ruta;
    public double costoTotal;
    public boolean exitoso;
    public String mensajeError;

    public PlanificacionResultado() {}

    public PlanificacionResultado(String idEnvio, String origen, String destino,
                                   int cantidadMaletas, List<String> ruta,
                                   double costoTotal, boolean exitoso) {
        this.idEnvio = idEnvio;
        this.origen = origen;
        this.destino = destino;
        this.cantidadMaletas = cantidadMaletas;
        this.ruta = ruta;
        this.costoTotal = costoTotal;
        this.exitoso = exitoso;
    }

    public static PlanificacionResultado fallido(String idEnvio, String origen,
                                                   String destino, String mensaje) {
        PlanificacionResultado r = new PlanificacionResultado();
        r.idEnvio = idEnvio;
        r.origen = origen;
        r.destino = destino;
        r.exitoso = false;
        r.mensajeError = mensaje;
        return r;
    }
}