package com.tasfb2b.planificador.model.dataset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "aeropuerto")
public class Aeropuerto {

    @Id
    @Column(name = "icao", nullable = false)
    private String codigo;

    @Column(name = "ciudad")
    private String ciudad;

    @Column(name = "pais")
    private String pais;

    @Column(name = "codigo_region")
    private String abreviatura;

    @Column(name = "huso_horario")
    private Integer offsetHorario;

    @Column(name = "capacidad_almacen")
    private Integer capacidad;

    @Column(name = "latitud")
    private Double latitud;

    @Column(name = "longitud")
    private Double longitud;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Transient
    private Integer id;

    @Transient
    private String continente;
}
