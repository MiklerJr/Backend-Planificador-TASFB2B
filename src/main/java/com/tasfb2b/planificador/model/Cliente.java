package com.tasfb2b.planificador.model;

import java.util.ArrayList;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "cliente")
public class Cliente {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotBlank(message = "El cliente debe tener un nombre")
    @Column(nullable = false)
    private String nombre;

    @NotBlank(message = "El cliente debe tener un apellido")
    @Column(nullable = false)
    private String apellido;

    @NotBlank(message = "El cliente debe tener un email")
    @Column(nullable = true)
    private String email;
    
    @NotBlank(message = "El cliente debe tener un número de teléfono")
    @Column(nullable = true)
    private String telefono;
    
    @NotBlank(message = "El cliente debe tener una contraseña")
    @Column(nullable = false)
    private String password;

    @OneToMany(mappedBy = "cliente")
    private ArrayList<Maleta> maletas = new ArrayList<>();
}
