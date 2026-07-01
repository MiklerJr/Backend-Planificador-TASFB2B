package com.tasfb2b.planificador.repository;

import com.tasfb2b.planificador.model.solucion.TramoRuta;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio JPA de {@link TramoRuta}. Existe como bean pero ningún componente del motor lo
 * inyecta todavía (la persistencia de soluciones va por {@code JdbcTemplate}).
 */
public interface TramoRutaRepository extends JpaRepository<TramoRuta, Integer> {
}
