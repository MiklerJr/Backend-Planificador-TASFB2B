package com.tasfb2b.planificador.dto.simulacion;

import lombok.Data;
import java.util.List;

import com.tasfb2b.planificador.dto.almacenes.AlertaAlmacen;
import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacen;
import com.tasfb2b.planificador.dto.vuelos.CargaVuelo;

@Data
public class BloqueSimulacion {
    /** Inicio de la ventana UTC del bloque (= scStart). El cursor de ventanas avanza en UTC, así
     *  que es un instante UTC real y los bloques son CONTIGUOS: {@code horaFin[N] == horaInicio[N+1]}.
     *  Sirve como reloj global para ubicar el bloque en la línea de tiempo. */
    private String horaInicio;
    /** Fin de la ventana UTC del bloque (= scEnd), en UTC. */
    private String horaFin;
    /** Alias UTC explícito de {@link #horaInicio} (mismo valor): inicio de la ventana UTC del
     *  bloque. Contiguo con el {@code horaFinUtc} del bloque anterior. */
    private String horaInicioUtc;
    /** Alias UTC explícito de {@link #horaFin} (mismo valor): fin de la ventana UTC del bloque. */
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
