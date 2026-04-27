package com.tasfb2b.planificador; // Ajusta esto a tu paquete real si es necesario

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. Apaga la protección CSRF para permitir peticiones POST desde Postman/Frontend
                .csrf(csrf -> csrf.disable())
                // 2. Permite que cualquier petición pase sin pedir contraseña
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}