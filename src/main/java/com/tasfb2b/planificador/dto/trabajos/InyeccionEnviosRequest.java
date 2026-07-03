package com.tasfb2b.planificador.dto.trabajos;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
public class InyeccionEnviosRequest {

    private List<Item> envios;

    @Data
    @NoArgsConstructor
    public static class Item {
        private String origen;
        private String destino;
        private int cantidad;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private LocalDateTime fechaHoraRegistro;
        private Integer clienteId;
        private String registrador;
        private String sede;
    }
}
