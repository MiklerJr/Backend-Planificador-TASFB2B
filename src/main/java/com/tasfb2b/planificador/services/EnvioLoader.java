package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.EnvioDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EnvioLoader {

    private final JdbcTemplate jdbcTemplate;

    public EnvioLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Método optimizado para cargar envíos desde PostgreSQL.
     * La base de datos se encarga de ordenar por fecha y limitar los resultados,
     * ahorrando muchísima memoria RAM en Java.
     */
    public List<EnvioDTO> cargarEnviosOptimizados(String origenICAO, int limite) {
        // Armamos el query. Si el límite es el valor máximo, no aplicamos LIMIT en SQL
        String limitClause = limite < Integer.MAX_VALUE ? " LIMIT " + limite : "";
        
        String sql = "SELECT id_envio, icao_destino, cantidad_maletas, hora_registro, minuto_registro " +
                     "FROM ENVIO " +
                     "WHERE icao_origen = ? " +
                     "ORDER BY fecha_hora_registro ASC" + limitClause;

        // jdbcTemplate.query mapea automáticamente cada fila devuelta a un EnvioDTO
        return jdbcTemplate.query(sql, (rs, rowNum) -> new EnvioDTO(
                rs.getString("id_envio"),
                rs.getString("icao_destino"),
                rs.getInt("cantidad_maletas"),
                rs.getInt("hora_registro"),
                rs.getInt("minuto_registro")
        ), origenICAO);
    }
}