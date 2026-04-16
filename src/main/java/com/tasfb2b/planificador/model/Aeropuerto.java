package com.tasfb2b.planificador.model;


import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
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
    @Column(nullable = false)
    private String ciudad;

    @NotBlank(message = "Al aeropuerto le corresponde un país")
    @Column(nullable = false)
    private String pais;

    @Column(nullable = false)
    private boolean activo = true;

    @NotBlank(message = "Al aeropuerto le corresponde un continente")
    @Column(nullable = false)
    private String continente;

    @NotBlank(message = "El aeropuerto debe tener una abreviatura")
    @Column(nullable = false, unique = true)
    private String abreviatura;

    @NotBlank(message = "El aeropuerto debe tener un codigo de identificación")
    @Column(nullable = false, unique = true)
    private String codigo;

    @NotNull(message = "El aeropuerto debe tener una offset de horario")
    @Column(nullable = false)
    private Integer offsetHorario;

    @NotNull(message = "El aeropuerto debe tener registrado una capacidad de almacenaje")
    @Column(nullable = false)
    private Integer capacidad;

    @NotNull(message = "El aeropuerto debe indicar su latitud")
    @Column(nullable = false)
    private Double latitud;

    @NotNull(message = "El aeropuerto debe indicar su longitud")
    @Column(nullable = false)
    private Double longitud;

    @OneToMany(mappedBy = "aeropuertoOrigen")
    private List<Vuelo> vuelosComoOrigen = new ArrayList<>();

    @OneToMany(mappedBy = "aeropuertoDestino")
    private List<Vuelo> vuelosComoDestino = new ArrayList<>();

}
