package com.tasfb2b.planificador.repository;

import com.tasfb2b.planificador.model.db.EnvioInyectado;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio JPA de {@link EnvioInyectado}. Parte de la "casa" model↔BD: existe como bean disponible
 * para lectura/auditoría, pero la escritura activa de la corrida la hace
 * {@code PersistenciaSolucionService.persistirInyecciones} por JdbcTemplate (para compartir la
 * serialización por corrida con el resto de tablas de solución).
 */
public interface EnvioInyectadoRepository extends JpaRepository<EnvioInyectado, Integer> {
}
