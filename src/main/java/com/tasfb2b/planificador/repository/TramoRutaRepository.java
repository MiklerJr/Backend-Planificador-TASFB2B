package com.tasfb2b.planificador.repository;

import com.tasfb2b.planificador.model.solucion.TramoRuta;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio JPA de {@link TramoRuta}. Estructura inerte de la "casa": existe como bean
 * pero ningún componente del motor lo inyecta todavía (se conectará en la Fase 4).
 */
public interface TramoRutaRepository extends JpaRepository<TramoRuta, Integer> {
}
