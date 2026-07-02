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
@Table(name = "ruta_asignada")
public class RutaAsignada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_ruta")
    private Integer idRuta;

    @Column(name = "id_envio", nullable = false)
    private String idEnvio;

    @Column(name = "activa", nullable = false)
    private boolean activa = true;

    @Column(name = "costo_total")
    private Double costoTotal;

    @Column(name = "duracion_horas")
    private Double duracionHoras;

    @Column(name = "cumple_sla")
    private Boolean cumpleSla;

    @Column(name = "slack_sla_min")
    private Integer slackSlaMin;

    @Column(name = "llegada_utc")
    private LocalDateTime llegadaUtc;

    @Column(name = "fecha_calculo")
    private LocalDateTime fechaCalculo;
}
