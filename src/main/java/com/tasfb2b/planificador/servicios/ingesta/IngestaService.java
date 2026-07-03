package com.tasfb2b.planificador.servicios.ingesta;

import com.tasfb2b.planificador.dto.datos.IngestaEstado;
import com.tasfb2b.planificador.servicios.MotorGrafoCache;
import com.tasfb2b.planificador.servicios.AlmacenCacheEsqueletos;
import com.tasfb2b.planificador.servicios.trabajos.RegistroTrabajos;
import com.tasfb2b.planificador.utilidades.analizador.AnalizadorAeropuertos;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class IngestaService {

    private final JdbcTemplate jdbc;
    private final MigradorEnviosDb migrador;
    private final AnalizadorAeropuertos aeropuertoParser;
    private final CargadorDatos cargadorDatos;
    private final RegistroTrabajos jobs;
    private final MotorGrafoCache motorCache;
    private final AlmacenCacheEsqueletos almacenEsqueletos;

    private final AtomicReference<IngestaEstado> estado = new AtomicReference<>();
    private final AtomicBoolean enCurso = new AtomicBoolean(false);

    public IngestaService(JdbcTemplate jdbc, MigradorEnviosDb migrador, AnalizadorAeropuertos aeropuertoParser,
                          CargadorDatos cargadorDatos, RegistroTrabajos jobs, MotorGrafoCache motorCache,
                          AlmacenCacheEsqueletos almacenEsqueletos) {
        this.jdbc = jdbc;
        this.migrador = migrador;
        this.aeropuertoParser = aeropuertoParser;
        this.cargadorDatos = cargadorDatos;
        this.jobs = jobs;
        this.motorCache = motorCache;
        this.almacenEsqueletos = almacenEsqueletos;
    }

    public boolean estaEnCurso() { return enCurso.get(); }
    public IngestaEstado getEstado() { return estado.get(); }

    public IngestaEstado iniciar(MultipartFile aeropuertos, MultipartFile vuelos, MultipartFile[] envios) {
        if (!jobs.listarActivos().isEmpty()) {
            throw new IllegalStateException("Hay una simulación activa; cancélala antes de cargar un dataset.");
        }
        if (vacio(aeropuertos) || vacio(vuelos) || envios == null || envios.length == 0) {
            throw new IllegalArgumentException("Se requieren los archivos de aeropuertos, vuelos y al menos uno de envíos.");
        }
        if (!enCurso.compareAndSet(false, true)) {
            throw new IllegalStateException("Ya hay una ingesta en curso.");
        }

        Path tmpAero = null, tmpVuelos = null;
        List<EnvioTemp> tmpEnvios = new ArrayList<>();
        try {
            tmpAero = aTemp(aeropuertos, "aeropuertos");
            tmpVuelos = aTemp(vuelos, "vuelos");
            for (MultipartFile e : envios) {
                if (vacio(e)) continue;
                String icao = MigradorEnviosDb.origenIcaoDeNombre(e.getOriginalFilename());
                if (icao == null) {
                    throw new IllegalArgumentException(
                            "Archivo de envíos sin ICAO derivable del nombre: " + e.getOriginalFilename()
                          + " (se espera _envios_<ICAO>_.txt)");
                }
                tmpEnvios.add(new EnvioTemp(aTemp(e, "envios"), icao));
            }
            if (tmpEnvios.isEmpty()) {
                throw new IllegalArgumentException("Ningún archivo de envíos válido.");
            }
        } catch (RuntimeException | IOException ex) {
            limpiar(tmpAero, tmpVuelos, tmpEnvios);
            enCurso.set(false);
            if (ex instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalStateException("No se pudieron preparar los archivos: " + ex.getMessage(), ex);
        }

        IngestaEstado e = new IngestaEstado();
        e.setInicio(LocalDateTime.now().toString());
        e.setEnviosArchivosTotal(tmpEnvios.size());
        estado.set(e);

        final Path fAero = tmpAero, fVuelos = tmpVuelos;
        final List<EnvioTemp> fEnvios = tmpEnvios;
        jobs.ejecutarTarea(() -> ejecutar(fAero, fVuelos, fEnvios, e));
        return e;
    }

    // ── Worker async ────────────────────────────────────────────────────────────

    private void ejecutar(Path aero, Path vuelos, List<EnvioTemp> envios, IngestaEstado e) {
        try {
            // 1. Reemplazo destructivo. CASCADE limpia las soluciones de la 5a (ruta_asignada/
            //    tramo_ruta/cancelacion_vuelo, FK a envio/vuelo) y envio_inyectado (FK a aeropuerto).
            e.setFase("limpiando");
            jdbc.execute("TRUNCATE aeropuerto, vuelo, envio RESTART IDENTITY CASCADE");

            // 2. Aeropuertos (parse robusto BOM-safe vía AnalizadorAeropuertos).
            e.setFase("aeropuertos");
            e.setAeropuertos(migrador.insertarAeropuertos(aeropuertoParser.parse(aero)));

            // 3. Vuelos.
            e.setFase("vuelos");
            try (Reader r = Files.newBufferedReader(vuelos, StandardCharsets.UTF_8)) {
                e.setVuelos(migrador.migrarVuelosDesde(r));
            }

            // 4. Envíos (el ICAO de origen viene del nombre del archivo).
            e.setFase("envios");
            long ins = 0, desc = 0;
            int procesados = 0;
            for (EnvioTemp et : envios) {
                try (Reader r = Files.newBufferedReader(et.path, StandardCharsets.UTF_8)) {
                    int[] res = migrador.migrarEnviosDesde(r, et.icao);
                    ins += res[0];
                    desc += res[1];
                }
                procesados++;
                e.setEnviosArchivosProcesados(procesados);
                e.setEnviosInsertados(ins);
                e.setEnviosDescartados(desc);
            }

            // 5. Recargar la cache del motor (aeropuertos, vuelos, ventanas) e invalidar el grafo +
            //    esqueletos compartidos: cambian los vuelos ⇒ los edge-idx cacheados ya no valen.
            e.setFase("recargando");
            cargadorDatos.load();
            motorCache.invalidar();
            // Los esqueletos persistidos también son del dataset viejo: borrar el archivo (la
            // huella de AlmacenCacheEsqueletos lo descartaría igual, pero así no quedan huérfanos).
            almacenEsqueletos.borrar();

            e.setFase("completada");
            log.info("Ingesta completada: {} aeropuertos, {} vuelos, {} envíos ({} descartados)",
                    e.getAeropuertos(), e.getVuelos(), e.getEnviosInsertados(), e.getEnviosDescartados());
        } catch (Exception ex) {
            e.setFase("error");
            e.setError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.error("Ingesta falló: {}", ex.getMessage(), ex);
        } finally {
            e.setFin(LocalDateTime.now().toString());
            e.setTerminado(true);
            limpiar(aero, vuelos, envios);
            enCurso.set(false);
        }
    }

    private static boolean vacio(MultipartFile f) {
        return f == null || f.isEmpty();
    }

    private static Path aTemp(MultipartFile f, String prefijo) throws IOException {
        Path tmp = Files.createTempFile("ingesta-" + prefijo + "-", ".txt");
        f.transferTo(tmp);
        return tmp;
    }

    private static void limpiar(Path aero, Path vuelos, List<EnvioTemp> envios) {
        borrar(aero);
        borrar(vuelos);
        if (envios != null) for (EnvioTemp et : envios) borrar(et.path);
    }

    private static void borrar(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // archivo temporal; se limpiará al salir de la JVM
        }
    }

    private record EnvioTemp(Path path, String icao) { }
}
