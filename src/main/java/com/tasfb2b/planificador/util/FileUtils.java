package com.tasfb2b.planificador.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class FileUtils {

    // Constructor privado para evitar instanciación
    private FileUtils() {
        throw new UnsupportedOperationException("Clase utilitaria");
    }

    public static List<String> leerLineasSeguro(Path file) throws IOException {
        try {
            // Intento 1: UTF-8 (El estándar mundial actual)
            List<String> lineas = Files.readAllLines(file, StandardCharsets.UTF_8);
            return limpiarLineas(lineas);
        } catch (java.nio.charset.MalformedInputException | java.nio.charset.UnmappableCharacterException e) {
            try {
                // Intento 2: Formato de Windows tradicional
                List<String> lineas = Files.readAllLines(file, Charset.forName("Windows-1252"));
                return limpiarLineas(lineas);
            } catch (Exception e2) {
                // Intento 3: UTF-16 (Por si acaso algún archivo viene así)
                List<String> lineas = Files.readAllLines(file, StandardCharsets.UTF_16);
                return limpiarLineas(lineas);
            }
        }
    }

    private static List<String> limpiarLineas(List<String> lineas) {
        return lineas.stream()
                .map(l -> l.replace("\u0000", "")  // Caracteres nulos UTF-16
                        .replace("\uFEFF", "")  // BOM invisible al final de línea
                        .replace("\u200B", "")  // Zero-width space
                        .trim())
                .collect(Collectors.toList());
    }
}