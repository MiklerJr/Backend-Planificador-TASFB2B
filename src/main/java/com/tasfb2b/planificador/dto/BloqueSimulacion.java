package com.tasfb2b.planificador.dto;

import lombok.Data;
import java.util.List;

@Data
public class BloqueSimulacion {
    /** Inicio del rango de datos consumidos, en hora LOCAL del eje de registro (= scStart).
     *  Ojo: mezcla husos (cada envío se registra en la hora local de su origen); no usar
     *  como reloj global. Para el eje UTC usar {@link #horaInicioUtc}. */
    private String horaInicio;
    /** Fin del rango de datos consumidos, en hora LOCAL del eje de registro (= scEnd). */
    private String horaFin;
    /** Rango UTC real de los registros contenidos en el bloque: el {@code registroUtc} más
     *  temprano entre sus asignaciones. Bien definido (a diferencia de convertir scStart, que
     *  mezcla husos). Útil para ubicar el bloque en el eje de tiempo global. Null si el bloque
     *  no tiene asignaciones con registro. */
    private String horaInicioUtc;
    /** Rango UTC real de los registros del bloque: el {@code registroUtc} más tardío. */
    private String horaFinUtc;
    /** Legacy: cantidad de envios/lotes procesados en este bloque (delta). */
    private int maletasProcesadas;
    /** Legacy: cantidad de envios/lotes enrutados en este bloque (delta). */
    private int maletasEnrutadas;
    /** Acumulado del job visible, en maletas individuales reales. */
    private long maletasProcesadasAcum;
    /** Acumulado enrutado del job visible, en maletas individuales reales. */
    private long maletasEnrutadasAcum;
    /** Acumulado entregado hasta horaFin, en maletas individuales reales. */
    private long maletasEntregadasAcum;
    private List<AsignacionMaleta> asignaciones;
    private List<CargaVuelo> cargasVuelos;
    private List<OcupacionAlmacen> ocupacionAlmacenes;
    /**
     * Alerta de almacén cerca de colapso de capacidad, ESPECÍFICA de este bloque (no un valor
     * global): el peor almacén del bloque por % de ocupación. Va embebida aquí para que el front
     * la muestre sincronizada con la animación de ESTE bloque, aunque el backend ya esté
     * procesando bloques futuros. Null si el bloque no tocó ningún almacén.
     */
    private AlertaAlmacen alertaAlmacen;

    // ── Modelo Ta/Sa: campos del eje real ──────────────────────────────
    /** Índice 0-based de este bloque en la simulación. */
    private int    bloqueIdx;
    /** {@code Ta} = duración real del procesamiento de este bloque, en ms. */
    private long   taMs;
    /** Cantidad de minutos de datos consumidos (Sc = K * Sa). */
    private int    scMinutos;

    private long tiempoProcesamientoMs;
}
