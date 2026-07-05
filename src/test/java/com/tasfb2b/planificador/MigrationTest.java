package com.tasfb2b.planificador;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.services.MigradorEnviosDb;
import com.tasfb2b.planificador.util.AeropuertoParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
public class MigrationTest {

    @Autowired
    private MigradorEnviosDb migrador;

    @Autowired
    private AeropuertoParser aeropuertoParser;

    @Test
    @Sql(scripts = "/schema.sql")
    public void runMigration() throws Exception {
        System.out.println("Iniciando migracion...");
        
        Path aeroFile = Paths.get("src/main/resources/data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt");
        List<Aeropuerto> aeropuertos = aeropuertoParser.parse(aeroFile);
        migrador.insertarAeropuertos(aeropuertos);
        System.out.println("Aeropuertos insertados: " + aeropuertos.size());

        migrador.migrarVuelos("src/main/resources/data/planes_vuelo.txt");
        System.out.println("Vuelos insertados.");

        migrador.migrarDirectorioCompleto("src/main/resources/data/_envios_preliminar_");
        System.out.println("Migracion completa.");
    }
}
