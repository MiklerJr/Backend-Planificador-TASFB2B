package com.tasfb2b.planificador.dto.dataset;

import lombok.Data;

/**
 * Estado de la ingesta de un dataset. Una ingesta a la vez; el worker async muta los
 * campos (volatile para visibilidad) y el front los consulta con {@code GET /dataset/cargar/estado}.
 */
@Data
public class IngestaEstado {
    /** encolada | limpiando | aeropuertos | vuelos | envios | recargando | completada | error */
    private volatile String fase = "encolada";
    private volatile int aeropuertos = 0;
    private volatile int vuelos = 0;
    private volatile int enviosArchivosTotal = 0;
    private volatile int enviosArchivosProcesados = 0;
    private volatile long enviosInsertados = 0L;
    private volatile long enviosDescartados = 0L;
    /** Mensaje de error cuando {@code fase = "error"}. */
    private volatile String error;
    private volatile boolean terminado = false;
    private volatile String inicio;
    private volatile String fin;

    /** Progreso aproximado [0..1] por fase (los envíos pesan la mayor parte). */
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
