package com.tasfb2b.planificador.dto.simulacion;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class EjecucionParametros {
    private Integer k;
    private String motor;
    private Long seed;
    private LocalDateTime fechaInicio;
    private Integer saMin;
    private Integer taSegundos;
    private Integer dias;

    private Double umbralColapso;

    private boolean procesamientoPrevio = false;
}
