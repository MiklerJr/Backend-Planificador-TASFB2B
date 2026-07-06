package com.tasfb2b.planificador;

import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.servicios.ingesta.MigradorEnviosDb;
import com.tasfb2b.planificador.utilidades.analizador.AnalizadorAeropuertos;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

// Utilidad de migración manual: arranca el contexto Spring (requiere BD accesible) y vuelca los TXT
// de data/ a la BD. Se deja @Disabled para no romper ./mvnw test; habilitar puntualmente a mano.
@Disabled("Migración manual: requiere BD accesible y data/ en la raíz del repo")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
public class MigrationTest {

    @Autowired
    private MigradorEnviosDb migrador;

    @Autowired
    private AnalizadorAeropuertos analizadorAeropuertos;

    @Test
    @Sql(scripts = "/schema.sql")
    public void runMigration() throws Exception {
        System.out.println("Iniciando migracion...");

        Path aeroFile = Paths.get("data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");
        List<Aeropuerto> aeropuertos = analizadorAeropuertos.parse(aeroFile);
        migrador.insertarAeropuertos(aeropuertos);
        System.out.println("Aeropuertos insertados: " + aeropuertos.size());

        migrador.migrarVuelos("data/planes_vuelo.txt");
        System.out.println("Vuelos insertados.");

        migrador.migrarDirectorioCompleto("data/_envios_preliminar_");
        System.out.println("Migracion completa.");
    }
}
