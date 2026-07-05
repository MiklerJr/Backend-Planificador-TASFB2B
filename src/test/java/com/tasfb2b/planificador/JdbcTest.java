package com.tasfb2b.planificador;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class JdbcTest {
    public static void main(String[] args) {
        String[] users = {"postgres", "1inf54", "Tasf_b2b", "1inf54.984.4d"};
        try {
            System.out.println("Creando base de datos en la VM (puerto 5433)...");
            Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5433/postgres", "postgres", "Furiousandfast.8");
            conn.createStatement().executeUpdate("CREATE DATABASE \"Tasf_b2b\"");
            System.out.println("Base de datos creada exitosamente.");
            conn.close();
        } catch (SQLException e) {
            System.out.println("FALLO: " + e.getMessage());
        }
    }
}
