package com.tasfb2b.planificador.model.dataset;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
