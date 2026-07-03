package com.tasfb2b.planificador.modelo.datos;

import java.time.LocalDateTime;

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
@Table(name = "vuelo")
public class Vuelo {

    @Id
    @Column(name = "id_vuelo", nullable = false)
    private String idVuelo;

    @Column(name = "icao_origen")
    private String origen;

    @Column(name = "icao_destino")
    private String destino;

    @Column(name = "capacidad_maxima")
    private Integer capacidad;

    @Transient
    private Integer id;

    @Transient
    private LocalDateTime fechaHoraSalida;

    @Transient
    private LocalDateTime fechaHoraLlegada;

    @Transient
    private Aeropuerto aeropuertoOrigen;

    @Transient
    private Aeropuerto aeropuertoDestino;
}
