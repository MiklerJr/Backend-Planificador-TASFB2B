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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Carga aeropuertos, vuelos y maletas en memoria al arrancar la app.
 *
 * <p>Las maletas se almacenan en una <b>lista plana ordenada</b> por
 * {@code fechaHoraRegistro}. {@link #getMaletasEnRango(LocalDateTime, LocalDateTime)}
 * usa búsqueda binaria para extraer un sub‑rango — sin granularizar a buckets,
 * lo que permite que el modelo Sa/Sc/K acepte <b>cualquier</b> valor de Sa
 * y Sc = K·Sa sin pérdidas ni duplicados, y con overhead mínimo (la lista
 * solo guarda referencias).
 */
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

    /**
     * Maletas en orden cronológico por {@code fechaHoraRegistro}. Estructura plana
     * para que {@code getMaletasEnRango} use {@code binarySearch} en O(log N) sin
     * granularizar a buckets fijos. Inmutable tras {@link #load()}.
     */
    private List<Maleta> maletasOrdenadas = Collections.emptyList();

    /** Comparator usado por la búsqueda binaria. */
    private static final Comparator<Maleta> POR_FECHA = Comparator.comparing(Maleta::getFechaHoraRegistro);

    @PostConstruct
    public void load() throws IOException {
        aeropuertos = aeropuertoParser.parse(Path.of(airportsFile));
        Map<String, Aeropuerto> aeropuertoMap = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigo, a -> a));

        vuelos = vueloParser.parse(Path.of(flightsFile), aeropuertoMap);

        // Acumular en una sola lista mientras parseamos cada archivo de envíos.
        // Pre-dimensionada generosamente para evitar reallocs caros (9-10M maletas).
        List<Maleta> todas = new ArrayList<>(10_000_000);
        Files.list(Path.of(baggageDir))
                .filter(p -> p.toString().toLowerCase().endsWith(".txt"))
                .forEach(file -> {
                    String nombreArchivo = file.getFileName().toString();
                    Matcher matcher = BAGGAGE_PATTERN.matcher(nombreArchivo.toUpperCase());
                    if (!matcher.find()) return;

                    Aeropuerto origen = aeropuertoMap.get(matcher.group(1));
                    if (origen == null) return;

                    try {
                        todas.addAll(maletaParser.parse(file, origen, aeropuertoMap));
                    } catch (IOException e) {
                        log.error("Error leyendo {}: {}", file, e.getMessage());
                    }
                });

        // Ordenar una sola vez por fecha de registro — clave para que getMaletasEnRango
        // use binarySearch sin necesidad de buckets.
        todas.sort(POR_FECHA);
        maletasOrdenadas = todas;

        log.info("=================================================");
        log.info("RESUMEN DE DATOS CARGADOS EN MEMORIA");
        log.info("Aeropuertos : {}", aeropuertos.size());
        log.info("Vuelos      : {}", vuelos.size());
        log.info("Maletas     : {} (lista plana ordenada por fechaHoraRegistro)",
                maletasOrdenadas.size());
        if (!maletasOrdenadas.isEmpty()) {
            log.info("Rango       : {} → {}",
                    maletasOrdenadas.get(0).getFechaHoraRegistro(),
                    maletasOrdenadas.get(maletasOrdenadas.size() - 1).getFechaHoraRegistro());
        }
        log.info("=================================================");
    }

    /** Fecha de registro de la primera maleta. Null si no hay datos. */
    public LocalDateTime getPrimeraVentana() {
        return maletasOrdenadas.isEmpty() ? null : maletasOrdenadas.get(0).getFechaHoraRegistro();
    }

    /** Fecha de registro de la última maleta. Null si no hay datos. */
    public LocalDateTime getUltimaVentana() {
        return maletasOrdenadas.isEmpty() ? null
                : maletasOrdenadas.get(maletasOrdenadas.size() - 1).getFechaHoraRegistro();
    }

    /**
     * Devuelve las maletas registradas en {@code [desde, hasta)} (eje de datos).
     *
     * <p>Implementación: dos búsquedas binarias sobre la lista plana ordenada,
     * resultado en O(log N + K) donde K es el número de maletas en el rango.
     * No hay granularización por buckets — cualquier Sa y Sc = K·Sa funcionan
     * exactamente sin pérdidas ni duplicados.
     *
     * <p>El resultado es una <b>vista</b> ({@code subList}) sobre la lista
     * interna; los consumidores no deben modificarla.
     *
     * @param desde inicio del rango (inclusive)
     * @param hasta fin del rango (exclusivo)
     */
    public List<Maleta> getMaletasEnRango(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null || !desde.isBefore(hasta) || maletasOrdenadas.isEmpty())
            return Collections.emptyList();

        int from = lowerBound(desde);
        int to   = lowerBound(hasta);
        if (from >= to) return Collections.emptyList();
        return maletasOrdenadas.subList(from, to);
    }

    /**
     * Primera posición {@code i} tal que {@code maletasOrdenadas.get(i).fechaHoraRegistro >= ts}.
     * Si todas son anteriores, devuelve {@code size()}.
     */
    private int lowerBound(LocalDateTime ts) {
        int lo = 0, hi = maletasOrdenadas.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (maletasOrdenadas.get(mid).getFechaHoraRegistro().isBefore(ts)) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Muestra pequeña para usos legacy (ACO heredado, diagnóstico). */
    public List<Maleta> getMaletasMuestra(int limite) {
        if (limite <= 0 || maletasOrdenadas.isEmpty()) return Collections.emptyList();
        return maletasOrdenadas.subList(0, Math.min(limite, maletasOrdenadas.size()));
    }

    public int getTotalMaletas() {
        return maletasOrdenadas.size();
    }

    public List<Aeropuerto> getAeropuertos() { return aeropuertos; }
    public List<Vuelo>      getVuelos()      { return vuelos; }
}
