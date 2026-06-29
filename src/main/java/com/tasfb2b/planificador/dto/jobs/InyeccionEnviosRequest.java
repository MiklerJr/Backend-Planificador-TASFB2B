package com.tasfb2b.planificador.dto.jobs;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Petición para agregar envíos EN VIVO durante una corrida (E1 async), enviada por el operador desde
 * el front a {@code POST /jobs/{jobId}/inyectar-envios}. Permite inyectar una lista en un solo
 * request; cada {@link Item} es un lote (origen→destino, N maletas) que se suma a la demanda real de
 * la ventana. Los inyectados solo valen para esa simulación y NO se persisten en el dataset maestro
 * {@code ENVIO} (ver {@code dto.EnvioInyectadoInfo} y la tabla {@code envio_inyectado}).
 */
@Data
@NoArgsConstructor
public class InyeccionEnviosRequest {

    /** Envíos a inyectar (se validan todos antes de encolar: todo-o-nada). */
    private List<Item> envios;

    @Data
    @NoArgsConstructor
    public static class Item {
        /** Código ICAO del aeropuerto de origen. */
        private String origen;
        /** Código ICAO del aeropuerto de destino (distinto del origen, RF02). */
        private String destino;
        /** Número de maletas del lote (debe ser > 0). */
        private int cantidad;
        /**
         * Momento de registro del envío, en <b>UTC</b> (mismo eje que el cursor de ventanas y que
         * {@code CancelacionVueloRequest.fechaHoraSalida}). Si es futura, el envío entra cuando el
         * cursor la alcanza; si es pasada/actual o se omite ({@code null}), entra en el próximo bloque.
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private LocalDateTime fechaHoraRegistro;
        /** Identificador del cliente (opcional). */
        private Integer clienteId;
        /**
         * E1 — Operación día a día: nombre/identificador del empleado registrador que dio de alta el
         * envío (opcional). Atribución para la prueba (uno por sede). Solo informativo: no afecta el
         * ruteo.
         */
        private String registrador;
        /**
         * E1 — Operación día a día: sede del registrador (p. ej. "Lima", "Buenos Aires", "Copenhague",
         * "Delhi"), opcional. Solo informativo.
         */
        private String sede;
    }
}
