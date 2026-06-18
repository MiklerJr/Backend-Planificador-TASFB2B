package com.tasfb2b.planificador.model;

import jakarta.persistence.Convert;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entidad mapeada a la tabla real {@code vuelo}. El back la usa como POJO (la llena
 * {@code DataLoader} vía JdbcTemplate). El mapeo JPA está alineado con las columnas
 * reales para {@code ddl-auto=validate}; los campos derivados van como {@link Transient}.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "vuelo")
public class Vuelo {

    /** PK real de la tabla (varchar, p. ej. "SKBO-SEQM-0830"). */
    @Id
    @Column(name = "id_vuelo", nullable = false)
    private String idVuelo;

    @Column(name = "icao_origen")
    private String origen;

    @Column(name = "icao_destino")
    private String destino;

    @Column(name = "capacidad_maxima")
    private Integer capacidad;

    /**
     * Id numérico heredado. La tabla real no tiene esta columna (su PK es {@code id_vuelo});
     * se conserva como Transient porque {@code AlgorithmMapper}/{@code SimulacionFormat} leen
     * {@code getId()} con guarda {@code != null} (en producción siempre es null).
     */
    @Transient
    private Integer id;

    /**
     * Derivados en RAM por {@code DataLoader} a partir de {@code hora_salida}/{@code hora_llegada}
     * (que en la BD son varchar) y {@code FLIGHT_BASE_DATE}. No son columnas → Transient.
     */
    @Transient
    private LocalDateTime fechaHoraSalida;

    @Transient
    private LocalDateTime fechaHoraLlegada;

    /** Resueltos desde la caché de aeropuertos por ICAO; no son FK por id en la tabla → Transient. */
    @Transient
    private Aeropuerto aeropuertoOrigen;

    @Transient
    private Aeropuerto aeropuertoDestino;
}
