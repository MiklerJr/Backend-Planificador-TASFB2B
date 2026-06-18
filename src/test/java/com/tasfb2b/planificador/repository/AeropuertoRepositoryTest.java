package com.tasfb2b.planificador.repository;

import com.tasfb2b.planificador.model.Aeropuerto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:aeropuertos;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AeropuertoRepositoryTest {

    @Autowired
    private AeropuertoRepository repository;

    @Test
    void crudAeropuerto() {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigo("TEST");
        aeropuerto.setCiudad("Ciudad Test");
        aeropuerto.setPais("Pais Test");
        aeropuerto.setContinente("AM");
        aeropuerto.setOffsetHorario(-5);
        aeropuerto.setCapacidad(1234);
        aeropuerto.setLatitud(-12.04);
        aeropuerto.setLongitud(-77.03);
        aeropuerto.setActivo(true);

        Aeropuerto guardado = repository.save(aeropuerto);

        assertEquals("TEST", guardado.getCodigo());
        assertTrue(repository.findById("TEST").isPresent());

        Aeropuerto encontrado = repository.findById("TEST").orElseThrow();
        encontrado.setCapacidad(4321);
        repository.save(encontrado);

        assertEquals(4321, repository.findById("TEST").orElseThrow().getCapacidad());

        repository.deleteById("TEST");
        assertFalse(repository.existsById("TEST"));
    }
}
