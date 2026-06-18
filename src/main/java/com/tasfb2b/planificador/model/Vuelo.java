package com.tasfb2b.planificador.model;

import jakarta.persistence.Convert;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "vuelo")
public class Vuelo {
    
    @Id
    @Column(name = "id_vuelo", nullable = false, unique = true)
    private String id;

    @NotNull
    @Column(name = "capacidad_maxima", nullable = false)
    private Integer capacidad;

    @NotBlank
    @Column(name = "icao_origen", nullable = false)
    private String origen;
    
    @NotBlank
    @Column(name = "icao_destino", nullable = false)
    private String destino;

    @NotNull
    @Convert(converter = com.tasfb2b.planificador.util.LocalDateTimeToTimeStringConverter.class)
    @Column(name = "hora_salida", nullable = false)
    private LocalDateTime fechaHoraSalida;

    @NotNull
    @Convert(converter = com.tasfb2b.planificador.util.LocalDateTimeToTimeStringConverter.class)
    @Column(name = "hora_llegada", nullable = false)
    private LocalDateTime fechaHoraLlegada;

    @Transient
    private Aeropuerto aeropuertoOrigen;

    @Transient
    private Aeropuerto aeropuertoDestino;
}
