package com.tasfb2b.planificador.dto.datos;

import lombok.Data;

@Data
public class IngestaEstado {
    private volatile String fase = "encolada";
    private volatile int aeropuertos = 0;
    private volatile int vuelos = 0;
    private volatile int enviosArchivosTotal = 0;
    private volatile int enviosArchivosProcesados = 0;
    private volatile long enviosInsertados = 0L;
    private volatile long enviosDescartados = 0L;
    private volatile String error;
    private volatile boolean terminado = false;
    private volatile String inicio;
    private volatile String fin;

    public double getProgreso() {
        return switch (fase) {
            case "encolada"   -> 0.0;
            case "limpiando"  -> 0.05;
            case "aeropuertos"-> 0.10;
            case "vuelos"     -> 0.15;
            case "envios"     -> enviosArchivosTotal == 0 ? 0.20
                    : 0.20 + 0.70 * ((double) enviosArchivosProcesados / enviosArchivosTotal);
            case "recargando" -> 0.95;
            case "completada" -> 1.0;
            default           -> terminado ? 1.0 : 0.0;
        };
    }
}
