package com.tasfb2b.planificador.dto.auditoria;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimacionAuditoria {
    private long filasEnvios;
    private int  csvEnvios;
    private long filasCancelaciones;
    private int  csvCancelaciones;
    private int  totalCsv;
    private int  filasPorArchivo;
    private String desdeEfectivo;
    private String hastaEfectivo;
    private boolean recortado;
}
