package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.Vuelo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DataLoader {

    @Value("${data.airports.file}")
    private String airportsFile;

    @Value("${data.flights.file}")
    private String flightsFile;

    @Value("${data.baggage.dir}")
    private String baggageDir;

    private final AeropuertoParser aeropuertoParser;
    private final FlightParser     vueloParser;
    private final BaggageParser    maletaParser;

    private static final Pattern BAGGAGE_PATTERN = Pattern.compile("_([A-Z]{4})_");

    public DataLoader(AeropuertoParser aeropuertoParser,
                      FlightParser vueloParser,
                      BaggageParser maletaParser) {
        this.aeropuertoParser = aeropuertoParser;
        this.vueloParser      = vueloParser;
        this.maletaParser     = maletaParser;
    }

    private List<Aeropuerto> aeropuertos = new ArrayList<>();
    private List<Vuelo>      vuelos      = new ArrayList<>();

    // Maletas indexadas por ventana de 10 min (TreeMap = ya ordenado por clave temporal).
    // Cada ventana solo existe en RAM mientras su lista tiene referencias; al liberarse la
    // lista desde el servicio, el GC puede reclamar esos objetos.
    private final TreeMap<LocalDateTime, List<Maleta>> maletasPorVentana = new TreeMap<>();

    @PostConstruct
    public void load() throws IOException {
        aeropuertos = aeropuertoParser.parse(Path.of(airportsFile));
        Map<String, Aeropuerto> aeropuertoMap = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigo, a -> a));

        vuelos = vueloParser.parse(Path.of(flightsFile), aeropuertoMap);

        int[] totalMaletas = {0};
        Files.list(Path.of(baggageDir))
                .filter(p -> p.toString().toLowerCase().endsWith(".txt"))
                .forEach(file -> {
                    String nombreArchivo = file.getFileName().toString();
                    Matcher matcher = BAGGAGE_PATTERN.matcher(nombreArchivo.toUpperCase());
                    if (!matcher.find()) return;

                    Aeropuerto origen = aeropuertoMap.get(matcher.group(1));
                    if (origen == null) return;

                    try {
                        List<Maleta> lote = maletaParser.parse(file, origen, aeropuertoMap);
                        // Indexar cada maleta en su ventana de 10 min en una sola pasada,
                        // sin crear una lista plana adicional.
                        for (Maleta m : lote) {
                            maletasPorVentana
                                    .computeIfAbsent(claveVentana(m.getFechaHoraRegistro()), k -> new ArrayList<>())
                                    .add(m);
                        }
                        totalMaletas[0] += lote.size();
                    } catch (IOException e) {
                        log.error("Error leyendo {}: {}", file, e.getMessage());
                    }
                });

        log.info("=================================================");
        log.info("RESUMEN DE DATOS CARGADOS EN MEMORIA");
        log.info("Aeropuertos : {}", aeropuertos.size());
        log.info("Vuelos      : {}", vuelos.size());
        log.info("Maletas     : {} en {} ventanas de 10 min", totalMaletas[0], maletasPorVentana.size());
        log.info("=================================================");
    }

    // Ventanas disponibles en orden cronológico (TreeMap garantiza el orden).
    public Set<LocalDateTime> getVentanas() {
        return maletasPorVentana.keySet();
    }

    /** Primera ventana cargada (la más antigua). Null si no hay datos. */
    public LocalDateTime getPrimeraVentana() {
        return maletasPorVentana.isEmpty() ? null : maletasPorVentana.firstKey();
    }

    /** Última ventana cargada (la más reciente). Null si no hay datos. */
    public LocalDateTime getUltimaVentana() {
        return maletasPorVentana.isEmpty() ? null : maletasPorVentana.lastKey();
    }

    // Devuelve las maletas de una ventana sin eliminarlas (permite re-ejecución).
    public List<Maleta> getMaletasVentana(LocalDateTime ventana) {
        return maletasPorVentana.getOrDefault(ventana, Collections.emptyList());
    }

    /**
     * Devuelve las maletas registradas en {@code [desde, hasta)} (eje de datos).
     *
     * <p>Usado por el modelo de planificación programada fija para consumir
     * Sc = K*Sa minutos por ejecución. Las claves del TreeMap están alineadas
     * a múltiplos de 10 min ({@link #claveVentana}); para evitar pérdidas o
     * duplicados, los argumentos {@code desde} y {@code hasta} también deben
     * estar alineados (Sa debe ser múltiplo de 10).
     *
     * @param desde inicio del rango (inclusive)
     * @param hasta fin del rango (exclusivo)
     */
    public List<Maleta> getMaletasEnRango(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !desde.isBefore(hasta))
            return Collections.emptyList();

        // subMap([desde, hasta)) sobre el TreeMap es O(log n) + iteración del rango.
        java.util.NavigableMap<LocalDateTime, List<Maleta>> sub =
                maletasPorVentana.subMap(desde, true, hasta, false);

        if (sub.isEmpty()) return Collections.emptyList();

        // Tamaño esperado: estimación de la suma de tamaños.
        int total = 0;
        for (List<Maleta> l : sub.values()) total += l.size();
        List<Maleta> result = new ArrayList<>(total);
        for (List<Maleta> l : sub.values()) result.addAll(l);
        return result;
    }

    // Muestra pequeña para ACO u otros usos que no requieren el dataset completo.
    public List<Maleta> getMaletasMuestra(int limite) {
        return maletasPorVentana.values().stream()
                .flatMap(List::stream)
                .limit(limite)
                .collect(Collectors.toList());
    }

    public int getTotalMaletas() {
        return maletasPorVentana.values().stream().mapToInt(List::size).sum();
    }

    public List<Aeropuerto> getAeropuertos() { return aeropuertos; }
    public List<Vuelo>      getVuelos()      { return vuelos; }

    private LocalDateTime claveVentana(LocalDateTime t) {
        return t.truncatedTo(ChronoUnit.HOURS).plusMinutes((t.getMinute() / 10) * 10L);
    }
}
