package com.tasfb2b.planificador.model;

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
 * Entidad mapeada a la tabla real {@code envio}: un <b>envío lógico</b> = un lote de N maletas
 * físicas con el mismo origen/destino/registro (NO una maleta individual). Antes se llamaba
 * {@code Maleta}, lo que inducía a confusión. El back la usa como POJO (la llena {@code DataLoader}
 * vía JdbcTemplate); el mapeo JPA está alineado con las columnas reales para {@code ddl-auto=validate}
 * y los campos derivados o resueltos en RAM van como {@link Transient}.
 */
@Data
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "envio")
public class Envio {

    /** PK real de la tabla (varchar, p. ej. "SKBO-12345"). */
    @Id
    @Column(name = "id_envio", nullable = false)
    private String idEnvio;

    @Column(name = "cantidad_maletas")
    private Integer cantidad;

    @Column(name = "fecha_hora_registro")
    private LocalDateTime fechaHoraRegistro;

    /**
     * Id numérico heredado. La PK real es {@code id_envio}; {@code DataLoader} setea aquí el
     * número del id para uso interno, así que se conserva como Transient (no es columna).
     */
    @Transient
    private Integer id;

    /** SLA en horas (24/48): derivado del tipo de envío, no es columna. */
    @Transient
    private Integer plazo;

    /** Derivado de los continentes de origen/destino, no es columna. */
    @Transient
    private TipoEnvio tipoEnvio;

    /** Resuelto en RAM; en {@code envio} solo existe {@code id_cliente}. → Transient. */
    @Transient
    private Cliente cliente;

    /** Resueltos desde la caché de aeropuertos por ICAO ({@code icao_origen}/{@code icao_destino}). */
    @Transient
    private Aeropuerto aeropuertoOrigen;

    @Transient
    private Aeropuerto aeropuertoDestino;
}
