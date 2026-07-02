package com.tasfb2b.planificador.model.dataset;

public enum TipoEnvio {

    INTRACONTINENTAL,
    INTERCONTINENTAL;

    public static TipoEnvio derivar(Aeropuerto origen, Aeropuerto destino) {
        return origen.getContinente().equals(destino.getContinente())
                ? INTRACONTINENTAL
                : INTERCONTINENTAL;
    }
}
