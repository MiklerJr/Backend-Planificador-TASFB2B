package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Vuelo;

/**
 * Validación de coherencia de un {@link Vuelo} al cargarlo desde la fuente de datos.
 *
 * <p>Reglas duras (un vuelo incoherente se descarta del dataset en RAM, ver {@code DataLoader}):
 * <ul>
 *   <li><b>Capacidad &gt; 0</b>: un vuelo sin asientos no puede transportar maletas.</li>
 *   <li><b>Origen ≠ destino</b>: un vuelo que sale y llega al mismo aeropuerto no tiene sentido.</li>
 * </ul>
 * Las horas de salida y llegada SÍ pueden diferir (es válido un vuelo que cruza de un día a otro),
 * por lo que NO se valida ninguna relación entre ellas.
 *
 * <p>Espejo del estilo de {@link EnvioValidator}: clase de utilidad sin estado.
 */
public final class VueloValidator {

    private VueloValidator() {}

    /** {@code true} si el vuelo es coherente (capacidad &gt; 0 y origen ≠ destino). */
    public static boolean esCoherente(Vuelo v) {
        return v != null
                && v.getCapacidad() != null && v.getCapacidad() > 0
                && v.getOrigen() != null && v.getDestino() != null
                && !v.getOrigen().equals(v.getDestino());
    }
}
