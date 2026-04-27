package com.tasfb2b.planificador.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanificadorAuditoriaCsvTest {

    private final AeropuertoLoader aeropuertoLoader = new AeropuertoLoader();
    private final GraphBuilder graphBuilder = new GraphBuilder();
    private final EnvioLoader envioLoader = new EnvioLoader();
    private final PlanificadorService planificadorService =
            new PlanificadorService(aeropuertoLoader, graphBuilder, envioLoader);

    @Test
    void exportaCsvAuditoria() throws Exception {
        String outputPath = "audits/planificaciones_auditoria_test.csv";

        String generado = planificadorService.exportarAuditoriaCsv(
                20,
                15,
                40,
                outputPath
        );

        Path path = Path.of(generado);
        assertTrue(Files.exists(path), "El CSV de auditoría debe existir");

        List<String> lineas = Files.readAllLines(path);
        assertTrue(lineas.size() > 1, "El CSV debe tener cabecera y al menos una fila de datos");
        assertTrue(lineas.get(0).startsWith("idEnvio,origen,destino,registroHHMM"),
                "La cabecera del CSV no es la esperada");

        imprimirResumenEjecutivo(lineas);
    }

    private void imprimirResumenEjecutivo(List<String> lineas) {
        String header = lineas.get(0);
        List<String> headerParts = splitCsvLine(header);
        Map<String, Integer> idx = indexarCabecera(headerParts);

        List<RowAudit> rows = new ArrayList<>();
        for (int i = 1; i < lineas.size(); i++) {
            List<String> row = splitCsvLine(lineas.get(i));
            rows.add(new RowAudit(
                    get(row, idx, "idEnvio"),
                    get(row, idx, "origen"),
                    get(row, idx, "destino"),
                    Boolean.parseBoolean(get(row, idx, "exitoso")),
                    get(row, idx, "motivoFalla"),
                    parseInt(get(row, idx, "scoreCalidad")),
                    parseInt(get(row, idx, "slackSlaMin"))
            ));
        }

        int total = rows.size();
        long exitos = rows.stream().filter(r -> r.exitoso).count();
        long fallidos = total - exitos;
        double pctExitos = total > 0 ? (exitos * 100.0 / total) : 0;
        double pctFallidos = total > 0 ? (fallidos * 100.0 / total) : 0;
        double scoreProm = rows.stream().mapToInt(r -> r.score).average().orElse(0);
        double slackProm = rows.stream().mapToInt(r -> r.slack).average().orElse(0);

        List<RowAudit> peores = rows.stream()
                .sorted(Comparator.comparingInt((RowAudit r) -> r.score)
                        .thenComparingInt(r -> r.slack))
                .limit(5)
                .toList();

        System.out.println("\n=== RESUMEN EJECUTIVO AUDITORIA ===");
        System.out.println("Total auditados: " + total);
        System.out.println("Exitosos: " + exitos + " (" + String.format("%.2f", pctExitos) + "%)");
        System.out.println("Fallidos: " + fallidos + " (" + String.format("%.2f", pctFallidos) + "%)");
        System.out.println("Score promedio: " + String.format("%.2f", scoreProm));
        System.out.println("Slack SLA promedio (min): " + String.format("%.2f", slackProm));
        System.out.println("Top 5 peores rutas:");
        for (RowAudit r : peores) {
            System.out.println("- " + r.idEnvio + " | " + r.origen + "->" + r.destino
                    + " | score=" + r.score + " | slack=" + r.slack
                    + " | motivo=" + (r.motivo == null || r.motivo.isBlank() ? "N/A" : r.motivo));
        }
        System.out.println("===================================\n");
    }

    private Map<String, Integer> indexarCabecera(List<String> headerParts) {
        return headerParts.stream()
                .collect(Collectors.toMap(v -> v, headerParts::indexOf));
    }

    private String get(List<String> row, Map<String, Integer> idx, String key) {
        Integer pos = idx.get(key);
        if (pos == null || pos < 0 || pos >= row.size()) {
            return "";
        }
        return row.get(pos);
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return 0;
        }
    }

    private List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static class RowAudit {
        final String idEnvio;
        final String origen;
        final String destino;
        final boolean exitoso;
        final String motivo;
        final int score;
        final int slack;

        RowAudit(String idEnvio, String origen, String destino, boolean exitoso, String motivo, int score, int slack) {
            this.idEnvio = idEnvio;
            this.origen = origen;
            this.destino = destino;
            this.exitoso = exitoso;
            this.motivo = motivo;
            this.score = score;
            this.slack = slack;
        }
    }
}
