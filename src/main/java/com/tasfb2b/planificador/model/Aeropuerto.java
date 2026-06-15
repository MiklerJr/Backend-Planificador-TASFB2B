package com.tasfb2b.planificador.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entidad mapeada a la tabla real {@code aeropuerto}. El back la usa como POJO
 * (la llena {@code DataLoader} vía JdbcTemplate); el mapeo JPA está alineado con
 * las columnas reales para que {@code ddl-auto=validate} confirme la correspondencia.
 * Los campos derivados o sin columna van como {@link Transient}.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "aeropuerto")
public class Aeropuerto {

    /** PK real de la tabla: el código ICAO. */
    @Id
    @Column(name = "icao", nullable = false)
    private String codigo;

    @Column(name = "ciudad")
    private String ciudad;

    @Column(name = "pais")
    private String pais;

    /** Columna {@code codigo_region} en la BD (el back la usa como "abreviatura"). */
    @Column(name = "codigo_region")
    private String abreviatura;

    @Column(name = "huso_horario")
    private Integer offsetHorario;

    @Column(name = "capacidad_almacen")
    private Integer capacidad;

    @Column(name = "latitud")
    private Double latitud;

    @Column(name = "longitud")
    private Double longitud;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    /**
     * {@code id_numero} es varchar nullable en la BD y el back no lo usa, así que
     * no se mapea: queda fuera del modelo JPA (Transient) para no exigir la columna.
     */
    @Transient
    private Integer id;

    /** Continente derivado del ICAO (no es columna real). */
    @Transient
    private String continente;
}
