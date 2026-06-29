package com.tasfb2b.planificador.repository;

import com.tasfb2b.planificador.model.solucion.RutaAsignada;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio JPA de {@link RutaAsignada}. Estructura inerte de la "casa": existe como bean
 * pero ningún componente del motor lo inyecta todavía (se conectará en la Fase 4).
 */
public interface RutaAsignadaRepository extends JpaRepository<RutaAsignada, Integer> {
}
