package com.tasfb2b.planificador.dto;

public class EnvioDTO {

    public String id;
    public String destinoICAO;
    public int cantidadMaletas;
    public int horaRegistro;
    public int minutoRegistro;

    public EnvioDTO(String id, String destino, int maletas, int hh, int mm) {
        this.id = id;
        this.destinoICAO = destino;
        this.cantidadMaletas = maletas;
        this.horaRegistro = hh;
        this.minutoRegistro = mm;
    }
}
