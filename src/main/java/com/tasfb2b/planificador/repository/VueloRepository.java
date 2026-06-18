package com.tasfb2b.planificador.repository;

import com.tasfb2b.planificador.model.Vuelo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VueloRepository extends JpaRepository<Vuelo, String> {
}
