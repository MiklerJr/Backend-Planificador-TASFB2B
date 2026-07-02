package com.tasfb2b.planificador.dto.simulacion;

import lombok.Data;
import java.util.List;

import com.tasfb2b.planificador.dto.almacenes.AlertaAlmacen;
import com.tasfb2b.planificador.dto.almacenes.OcupacionAlmacen;
import com.tasfb2b.planificador.dto.vuelos.CargaVuelo;

@Data
public class BloqueSimulacion {
    private String horaInicio;
    private String horaFin;
    private String horaInicioUtc;
    private String horaFinUtc;
    private int maletasProcesadas;
    private int maletasEnrutadas;
    private long maletasProcesadasAcum;
    private long maletasEnrutadasAcum;
    private long maletasEntregadasAcum;
    private List<AsignacionMaleta> asignaciones;
    private List<CargaVuelo> cargasVuelos;
    private List<OcupacionAlmacen> ocupacionAlmacenes;
    private AlertaAlmacen alertaAlmacen;

    // ── Modelo Ta/Sa: campos del eje real ──────────────────────────────
    private int    bloqueIdx;
    private long   taMs;
    private int    scMinutos;

    private long tiempoProcesamientoMs;
}
