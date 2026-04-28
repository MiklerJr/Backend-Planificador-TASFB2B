package com.tasfb2b.planificador.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Muestra resumida de un envío procesado por el escenario 2 con motor ALNS.
 * Pensado como vista rápida (max 25 filas) para inspección humana — para
 * análisis estadístico completo usar {@link AuditoriaEnvio}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MuestraEnvio {
    /** Id del envío (LuggageBatch.id, ej "000000001"). */
    private String  idMaleta;
    /** Id del cliente que originó el envío (puede ser null si no está propagado). */
    private Integer idCliente;
    private String  origen;
    private String  destino;
    /** Cantidad de maletas en el envío. */
    private int     cantidad;
    /** Ruta tomada como cadena ICAO->ICAO->...; "sin ruta" si no se enrutó. */
    private String  ruta;
    /** Tiempo total de tránsito en minutos (vuelo + esperas). */
    private int     tiempoTotalMin;
    /** Plazo SLA aplicable, en minutos. */
    private int     slaLimiteMin;
    /** true si {@code tiempoTotal ≤ slaLimite}. */
    private boolean cumpleSLA;
    /** true si fue enrutado pero excedió el SLA (=enrutado y NO cumpleSLA). */
    private boolean tardado;
}
