package com.tasfb2b.planificador.util;

import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.model.Maleta;
import com.tasfb2b.planificador.model.Vuelo;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j; // <-- Importar

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final FlightParser      vueloParser;
    private final BaggageParser     maletaParser;

    // Compilamos la expresión regular una sola vez
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
    private List<Maleta>     maletas     = new ArrayList<>();

    @PostConstruct
    public void load() throws IOException {
        aeropuertos = aeropuertoParser.parse(Path.of(airportsFile));
        Map<String, Aeropuerto> aeropuertoMap = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigo, a -> a));

        vuelos = vueloParser.parse(Path.of(flightsFile), aeropuertoMap);

        Files.list(Path.of(baggageDir))
                .filter(p -> p.toString().toLowerCase().endsWith(".txt")) // Evita problemas con ".TXT"
                .forEach(file -> {
                    String nombreArchivo = file.getFileName().toString();
                    Matcher matcher = BAGGAGE_PATTERN.matcher(nombreArchivo.toUpperCase());

                    if (!matcher.find()) return;

                    String codigo = matcher.group(1);
                    Aeropuerto origen = aeropuertoMap.get(codigo);

                    if (origen != null) {
                        try {
                            maletas.addAll(maletaParser.parse(file, origen, aeropuertoMap));
                        } catch (IOException e) {
                            // Mejor usar Logger, pero esto es más seguro que System.err
                            System.err.println("Error leyendo " + file + ": " + e.getMessage());
                        }
                    }
                });

        System.out.printf("Cargados: %d aeropuertos, %d vuelos, %d maletas%n",
                aeropuertos.size(), vuelos.size(), maletas.size());

        log.info("=================================================");
        log.info("✈️ RESUMEN DE DATOS CARGADOS EN MEMORIA ✈️");
        log.info("Aeropuertos: {}", aeropuertos.size());
        log.info("Vuelos: {}", vuelos.size());
        log.info("Maletas: {}", maletas.size());
        log.info("=================================================");
    }

    public List<Aeropuerto> getAeropuertos() { return aeropuertos; }
    public List<Vuelo>      getVuelos()      { return vuelos; }
    public List<Maleta>     getMaletas()     { return maletas; }
}