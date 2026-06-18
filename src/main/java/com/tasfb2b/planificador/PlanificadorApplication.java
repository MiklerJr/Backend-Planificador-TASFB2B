package com.tasfb2b.planificador;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import com.tasfb2b.planificador.services.MigradorEnviosDb;

import java.util.Arrays;

@SpringBootApplication
public class PlanificadorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanificadorApplication.class, args);
    }

    // --- REEMPLAZA EL BEAN ANTERIOR POR ESTE ---
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        // Agregamos los puertos típicos de React/Vite
        config.setAllowedOrigins(Arrays.asList("http://localhost:5173", "http://localhost:3000"));
        config.setAllowedHeaders(Arrays.asList("Origin", "Content-Type", "Accept", "Authorization"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "OPTIONS", "DELETE", "PATCH"));
        // Headers que el front necesita leer desde fetch (CSV row counts y
        // nombre de archivo del attachment). Sin esto el navegador los oculta.
        config.setExposedHeaders(Arrays.asList(
                "X-Audit-Rows", "X-Muestra-Rows", "Content-Disposition"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

}
