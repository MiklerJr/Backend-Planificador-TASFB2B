package com.tasfb2b.planificador.repository;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Vuelo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vuelos;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class VueloRepositoryTest {

    @Autowired
    private VueloRepository vueloRepository;

    @Autowired
    private AeropuertoRepository aeropuertoRepository;

    @Test
    void crudVuelo() {
        Aeropuerto origen = aeropuerto("A001");
        Aeropuerto destino = aeropuerto("D001");
        aeropuertoRepository.save(origen);
        aeropuertoRepository.save(destino);

        Vuelo vuelo = new Vuelo();
        vuelo.setId("TEST-001");
        vuelo.setCapacidad(200);
        vuelo.setOrigen(origen.getCodigo());
        vuelo.setDestino(destino.getCodigo());
        vuelo.setFechaHoraSalida(LocalDateTime.of(2026, 1, 1, 10, 0));
        vuelo.setFechaHoraLlegada(LocalDateTime.of(2026, 1, 1, 12, 0));

        Vuelo guardado = vueloRepository.save(vuelo);

        assertEquals("TEST-001", guardado.getId());
        assertTrue(vueloRepository.findById("TEST-001").isPresent());

        Vuelo encontrado = vueloRepository.findById("TEST-001").orElseThrow();
        encontrado.setCapacidad(350);
        vueloRepository.save(encontrado);

        assertEquals(350, vueloRepository.findById("TEST-001").orElseThrow().getCapacidad());

        vueloRepository.deleteById("TEST-001");
        assertFalse(vueloRepository.existsById("TEST-001"));
    }

    private static Aeropuerto aeropuerto(String codigo) {
        Aeropuerto a = new Aeropuerto();
        a.setCodigo(codigo);
        a.setCiudad("Ciudad");
        a.setPais("Pais");
        a.setContinente("AM");
        a.setOffsetHorario(-5);
        a.setCapacidad(1000);
        a.setLatitud(-12.0);
        a.setLongitud(-77.0);
        a.setActivo(true);
        return a;
    }
}
