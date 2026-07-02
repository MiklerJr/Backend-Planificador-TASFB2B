package com.tasfb2b.planificador.model.dataset;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "envio")
public class Envio {

    @Id
    @Column(name = "id_envio", nullable = false)
    private String idEnvio;

    @Column(name = "cantidad_maletas")
    private Integer cantidad;

    @Column(name = "fecha_hora_registro")
    private LocalDateTime fechaHoraRegistro;

    @Transient
    private Integer id;

    @Transient
    private Integer plazo;

    @Transient
    private TipoEnvio tipoEnvio;

    @Transient
    private Cliente cliente;

    @Transient
    private Aeropuerto aeropuertoOrigen;

    @Transient
    private Aeropuerto aeropuertoDestino;
}
