package com.tasfb2b.planificador.model.db;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entidad de la tabla {@code cancelacion_vuelo}: cancelación de un vuelo-recurrente en un día
 * concreto (modelo "vuelo-día"). Estructura inerte de la "casa": aún no la usa el motor.
 *
 * <p>Distinta del DTO {@code dto.VueloCancelado} y de {@code dto.CancelacionVueloRequest}, que
 * siguen manejando las cancelaciones en memoria; esta es la entidad persistida.
 */
@Data
@Entity
@Table(name = "cancelacion_vuelo")
public class CancelacionVuelo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_cancelacion")
    private Integer idCancelacion;

    /** FK → vuelo(id_vuelo) (varchar). ON DELETE CASCADE en la BD. */
    @Column(name = "id_vuelo", nullable = false)
    private String idVuelo;

    @Column(name = "fecha_cancelacion", nullable = false)
    private LocalDate fechaCancelacion;
}
