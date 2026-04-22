package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.EnvioDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnvioLoaderTest {

    private EnvioLoader loader;

    @BeforeEach
    void setUp() {
        loader = new EnvioLoader();
    }

    @Test
    void testCargarEnviosSKBO() {
        List<EnvioDTO> envios = loader.cargarEnvios("SKBO");

        assertNotNull(envios, "La lista no debe ser null");
        assertFalse(envios.isEmpty(), "Debe cargar envíos");

        System.out.println("Cargados " + envios.size() + " envíos desde SKBO");

        EnvioDTO primero = envios.get(0);
        assertNotNull(primero.id, "Debe tener ID");
        assertNotNull(primero.destinoICAO, "Debe tener destino");
        assertTrue(primero.cantidadMaletas > 0, "Debe tener cantidad maletas");
    }

    @Test
    void testCargarPrimeros5Envios() {
        List<EnvioDTO> envios = loader.cargarEnvios("SKBO");

        assertTrue(envios.size() >= 5, "Debe tener al menos 5 envíos");

        for (int i = 0; i < 5 && i < envios.size(); i++) {
            EnvioDTO e = envios.get(i);
            System.out.println("Envio " + (i+1) + ": " + e.id +
                    " -> " + e.destinoICAO +
                    " (" + e.cantidadMaletas + " maletas)");
        }
    }

    @Test
    void testArchivoNoExistente() {
        List<EnvioDTO> envios = loader.cargarEnvios("NOEX");

        assertNotNull(envios, "Debe retornar lista vacía, no null");
        assertTrue(envios.isEmpty(), "Debe estar vacío para archivo inexistente");
    }
}