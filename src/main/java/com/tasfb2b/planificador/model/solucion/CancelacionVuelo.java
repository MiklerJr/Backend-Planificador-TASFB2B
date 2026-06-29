package com.tasfb2b.planificador.model.solucion;

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
 * concreto (modelo "vuelo-día"). La tabla la escribe
 * {@code PersistenciaSolucionService.persistirCancelaciones} por {@code JdbcTemplate} cuando el
 * usuario cancela un vuelo EN VIVO (mismo patrón que {@code envio_inyectado}); esta entidad y su
 * repositorio existen como mapeo pero el motor NO los inyecta para escribir.
 *
 * <p>Distinta del DTO {@code dto.VueloCancelado} y de {@code dto.CancelacionVueloRequest}, que
 * manejan las cancelaciones en memoria durante la corrida; esta es la fila persistida.
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

    /** Envíos que estaban comprometidos en ese vuelo-día y se devolvieron al backlog para re-enrutar. */
    @Column(name = "envios_afectados", nullable = false)
    private Integer enviosAfectados;
}
