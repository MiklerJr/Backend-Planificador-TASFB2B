package com.tasfb2b.planificador.model.solucion;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "cancelacion_vuelo")
public class CancelacionVuelo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_cancelacion")
    private Integer idCancelacion;

    @Column(name = "id_vuelo", nullable = false)
    private String idVuelo;

    @Column(name = "fecha_cancelacion", nullable = false)
    private LocalDate fechaCancelacion;

    @Column(name = "envios_afectados", nullable = false)
    private Integer enviosAfectados;
}
