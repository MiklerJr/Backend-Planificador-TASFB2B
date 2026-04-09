package com.tasfb2b.planificador.model;

import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotNull
    @Column(nullable = false)
    private Integer capacidad;

    @NotBlank
    @Column(nullable = false)
    private String origen;
    
    @NotBlank
    @Column(nullable = false)
    private String destino;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime fechaHoraSalida;

    @NotNull
    @Column(nullable = false)
    private LocalDateTime fechaHoraLlegada;

    @NotNull
    @ManyToOne
    @JoinColumn(name = "aeropuerto_origen_id", nullable = false)
    private Aeropuerto aeropuertoOrigen;
    
    @NotNull
    @ManyToOne
    @JoinColumn(name = "aeropuerto_destino_id", nullable = false)
    private Aeropuerto aeropuertoDestino;
}
