package com.tasfb2b.planificador.repository;

import com.tasfb2b.planificador.model.solucion.CancelacionVuelo;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio JPA de {@link CancelacionVuelo}. Existe como bean pero el motor NO lo inyecta: la
 * tabla {@code cancelacion_vuelo} la escribe {@code PersistenciaSolucionService.persistirCancelaciones}
 * por {@code JdbcTemplate} (mismo patrón que {@code EnvioInyectadoRepository}). Disponible para
 * lecturas JPA si en el futuro hiciera falta.
 */
public interface CancelacionVueloRepository extends JpaRepository<CancelacionVuelo, Integer> {
}
