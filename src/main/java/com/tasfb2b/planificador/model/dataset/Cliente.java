package com.tasfb2b.planificador.model.dataset;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POJO de cliente. La BD <b>no</b> tiene tabla {@code cliente} (la tabla {@code envio}
 * solo guarda {@code id_cliente}), por eso NO es una entidad JPA: se usa únicamente en
 * memoria para transportar el id del cliente del envío. Mantenerla como @Entity haría
 * fallar {@code ddl-auto=validate} al arrancar.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cliente {
    private Integer id;
    private String nombre;
    private String apellido;
    private String email;
    private String telefono;
    private String password;
}
