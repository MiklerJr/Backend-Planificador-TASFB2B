package com.tasfb2b.planificador.model;


import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name="aeropuerto")
public class Aeropuerto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @NotBlank(message = "Al aeropuerto le corresponde una ciudad")
    @Column(nullable = false, unique = true)
    private String ciudad;

    @NotBlank(message = "Al aeropuerto le corresponde un país")
    @Column(nullable = false, unique = true)
    private String pais;

    @NotBlank(message = "El aeropuerto debe tener un estado")
    @Column(nullable = false)
    private String estado;

    @NotBlank(message = "Al aeropuerto le corresponde un continente")
    @Column(nullable = false)
    private String continente;

    @NotBlank(message = "El aeropuerto debe tener una abreviatura")
    @Column(nullable = false, unique = true)
    private String abreviatura;

    @NotBlank(message = "El aeropuerto debe tener un codigo de identificación")
    @Column(nullable = false, unique = true)
    private String codigo;

    @NotBlank(message = "El aeropuerto debe tener registrado una capacidad de almacenaje")
    @Column(nullable = false)
    private int capacidad;

    @Column(nullable = false, unique = true)
    private float latitud;

    @Column(nullable = false, unique = true)
    private float longitud;

}
