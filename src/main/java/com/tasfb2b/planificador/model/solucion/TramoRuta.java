package com.tasfb2b.planificador.model.solucion;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entidad de la tabla {@code tramo_ruta}: un vuelo dentro de una ruta, ordenado por
 * {@code numero_orden} (0,1,2...). Estructura inerte de la "casa": aún no la usa el motor.
 *
 * <p>Distinta del DTO {@code dto.TramoRuta} (read-model para el front); esta es la entidad
 * persistida.
 */
@Data
@Entity
@Table(name = "tramo_ruta")
public class TramoRuta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_tramo")
    private Integer idTramo;

    /** FK → ruta_asignada(id_ruta). ON DELETE CASCADE en la BD. */
    @Column(name = "id_ruta", nullable = false)
    private Integer idRuta;

    @Column(name = "numero_orden", nullable = false)
    private Integer numeroOrden;

    /** FK → vuelo(id_vuelo) (varchar). ON DELETE CASCADE en la BD. */
    @Column(name = "id_vuelo")
    private String idVuelo;

    @Column(name = "hora_salida_utc")
    private LocalDateTime horaSalidaUtc;

    @Column(name = "hora_llegada_utc")
    private LocalDateTime horaLlegadaUtc;
}
