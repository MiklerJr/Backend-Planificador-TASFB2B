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
@Table(name = "envio_inyectado")
public class EnvioInyectado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_inyeccion")
    private Integer idInyeccion;

    @Column(name = "id_envio", nullable = false)
    private String idEnvio;

    @Column(name = "icao_origen")
    private String icaoOrigen;

    @Column(name = "icao_destino")
    private String icaoDestino;

    @Column(name = "cantidad_maletas")
    private Integer cantidadMaletas;

    @Column(name = "id_cliente")
    private Integer idCliente;

    @Column(name = "ready_time_utc")
    private LocalDateTime readyTimeUtc;

    @Column(name = "sla_horas")
    private Integer slaHoras;

    @Column(name = "bloque_idx")
    private Integer bloqueIdx;

    @Column(name = "registrador")
    private String registrador;

    @Column(name = "sede")
    private String sede;
}
