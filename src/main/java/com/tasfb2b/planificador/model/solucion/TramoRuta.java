package com.tasfb2b.planificador.model.solucion;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "tramo_ruta")
public class TramoRuta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_tramo")
    private Integer idTramo;

    @Column(name = "id_ruta", nullable = false)
    private Integer idRuta;

    @Column(name = "numero_orden", nullable = false)
    private Integer numeroOrden;

    @Column(name = "id_vuelo")
    private String idVuelo;

    @Column(name = "hora_salida_utc")
    private LocalDateTime horaSalidaUtc;

    @Column(name = "hora_llegada_utc")
    private LocalDateTime horaLlegadaUtc;
}
