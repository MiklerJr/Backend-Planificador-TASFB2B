package com.tasfb2b.planificador.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    private Integer id;

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

    @NotBlank(message = "El aeropuerto debe tener una offset de horario")
    @Column(nullable = false)
    private String offsetHorario;

    @NotNull(message = "El aeropuerto debe tener registrado una capacidad de almacenaje")
    @Column(nullable = false)
    private Integer capacidad;

    @NotNull(message = "El aeropuerto debe indicar su latitud")
    @Column(nullable = false, unique = true)
    private Double latitud;

    @NotNull(message = "El aeropuerto debe indicar su longitud")
    @Column(nullable = false, unique = true)
    private Double longitud;

}
