package com.tasfb2b.planificador.dto.jobs;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnvioInyectadoInfo {
    private String idEnvio;
    private String origen;
    private String destino;
    private int cantidad;
    private Integer clienteId;
    private int slaHoras;
    private String readyTimeUtc;
    private int bloqueIdx;
    private String registrador;
    private String sede;
}
