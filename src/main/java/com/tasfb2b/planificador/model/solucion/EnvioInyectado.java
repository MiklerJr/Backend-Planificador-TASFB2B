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
 * Entidad de la tabla {@code envio_inyectado}: envío agregado EN VIVO por el operador durante una
 * corrida (no pertenece al dataset maestro {@code ENVIO}). La tabla se vacía al iniciar otra corrida
 * ({@code TRUNCATE} en {@code PersistenciaSolucionService.iniciarCorrida}), por eso "solo vale para
 * esa simulación".
 *
 * <p>Parte de la "casa" model↔BD, igual que {@link CancelacionVuelo}; la escritura activa la hace
 * {@code PersistenciaSolucionService.persistirInyecciones} por JdbcTemplate (no este repositorio).
 * Distinta del DTO {@code dto.EnvioInyectadoInfo} (eco en vivo de {@code /estado}) y de
 * {@code dto.InyeccionEnviosRequest} (request del operador).
 *
 * <p>{@code idEnvio} es sintético ({@code "INV-bloque-n"}) y NO referencia {@code envio(id_envio)}:
 * el inyectado no existe en el dataset maestro.
 */
@Data
@Entity
@Table(name = "envio_inyectado")
public class EnvioInyectado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_inyeccion")
    private Integer idInyeccion;

    /** Id sintético del envío inyectado ("INV-bloque-n"). NO es FK a envio(id_envio). */
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

    /** readyTime efectivo del envío inyectado, en UTC. */
    @Column(name = "ready_time_utc")
    private LocalDateTime readyTimeUtc;

    @Column(name = "sla_horas")
    private Integer slaHoras;

    /** Índice del bloque en que el envío entró a la simulación. */
    @Column(name = "bloque_idx")
    private Integer bloqueIdx;

    /**
     * E1 — Operación día a día: empleado registrador que dio de alta el envío (opcional). Columna
     * añadida por migración (ver docs/db/schema.sql): {@code ALTER TABLE envio_inyectado ADD COLUMN
     * registrador VARCHAR}.
     */
    @Column(name = "registrador")
    private String registrador;

    /** E1 — Operación día a día: sede del registrador (opcional, p. ej. "Lima"). */
    @Column(name = "sede")
    private String sede;
}
