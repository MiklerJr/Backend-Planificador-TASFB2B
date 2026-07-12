package com.tasfb2b.planificador.utilidades;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class UtilidadesArchivo {

    private UtilidadesArchivo() {
        throw new UnsupportedOperationException("Clase utilitaria");
    }

    public static List<String> leerLineasSeguro(Path file) throws IOException {
        try {
            List<String> lineas = Files.readAllLines(file, StandardCharsets.UTF_8);
            return limpiarLineas(lineas);
        } catch (java.nio.charset.MalformedInputException | java.nio.charset.UnmappableCharacterException e) {
            try {
                List<String> lineas = Files.readAllLines(file, Charset.forName("Windows-1252"));
                return limpiarLineas(lineas);
            } catch (Exception e2) {
                List<String> lineas = Files.readAllLines(file, StandardCharsets.UTF_16);
                return limpiarLineas(lineas);
            }
        }
    }

    private static List<String> limpiarLineas(List<String> lineas) {
        return lineas.stream()
                .map(l -> l.replace("\u0000", "")
                        .replace("\uFEFF", "")
                        .replace("\u200B", "")
                        .trim())
                .collect(Collectors.toList());
    }
}