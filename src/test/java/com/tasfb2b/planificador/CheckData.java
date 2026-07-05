package com.tasfb2b.planificador;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class CheckData {
    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5433/Tasf_b2b", "postgres", "Furiousandfast.8");
             Statement stmt = conn.createStatement()) {
            
            ResultSet rs1 = stmt.executeQuery("SELECT count(*) FROM aeropuerto");
            if (rs1.next()) System.out.println("Aeropuertos: " + rs1.getInt(1));
            
            ResultSet rs2 = stmt.executeQuery("SELECT count(*) FROM vuelo");
            if (rs2.next()) System.out.println("Vuelos: " + rs2.getInt(1));
            
            ResultSet rs3 = stmt.executeQuery("SELECT count(*) FROM envio");
            if (rs3.next()) System.out.println("Envios: " + rs3.getInt(1));
        }
    }
}
