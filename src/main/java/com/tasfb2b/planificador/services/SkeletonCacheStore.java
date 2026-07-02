package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.model.dataset.Aeropuerto;
import com.tasfb2b.planificador.model.dataset.Vuelo;
import com.tasfb2b.planificador.util.DataLoader;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Persistencia a disco de la caché de esqueletos de {@link MotorGrafoCache} para que el pre-warm
 * (Fase T) sobreviva a los reinicios del proceso: en el despliegue (VM de 2 vCPU) el calentamiento
 * con caché fría cuesta minutos de Dijkstra, y hoy se pierde con cada restart del contenedor.
 *
 * <p>Seguridad ante cambios de dataset: el archivo lleva una <b>huella</b> (SHA-256 de aeropuertos
 * y vuelos, ya ordenados por {@code DataLoader} con ORDER BY). Antes de cargar se recalcula la
 * huella contra lo que hay AHORA en la BD; si no coincide (ingesta de por medio, archivo copiado de
 * otro dataset), el archivo se ignora y se sigue con caché vacía, como hoy. Los esqueletos son
 * secuencias de {@code Edge.idx}, así que su validez depende SOLO de la malla de vuelos —que la
 * huella cubre por completo—, nunca de la demanda. Un archivo corrupto/truncado también se ignora
 * (lectura best-effort) y la escritura es atómica (tmp + move), así que nunca queda un archivo a
 * medias con huella válida.
 *
 * <p>Ciclo: {@code ApplicationReadyEvent} carga el archivo si la huella coincide;
 * {@link #guardarSiCrecio()} lo reescribe cuando la caché ganó claves (tras el pre-warm y al final
 * de cada corrida, ver {@code PlanificadorService}); la ingesta lo {@link #borrar() borra} junto
 * con {@code MotorGrafoCache.invalidar()}. Reversible con {@code planificador.cache.skeleton-file}
 * vacío (comportamiento previo: caché solo en RAM).
 */
@Slf4j
@Component
public class SkeletonCacheStore {

    /** Cabecera del archivo. Si cambia el formato en disco, subir VERSION descarta los archivos viejos. */
    static final int MAGIC = 0x54534B31;   // "TSK1"
    static final int VERSION = 1;

    // Cotas de sanidad al leer (hoy: máx 8 esqueletos/clave y rutas de pocos tramos; ver
    // GreedyRepairOperator.MAX_SKELETONS_POR_CLAVE). Un valor fuera de rango = archivo corrupto.
    private static final int MAX_SKELETONS_LEIDOS = 64;
    private static final int MAX_TRAMOS_LEIDOS = 4096;

    private final DataLoader dataLoader;
    private final MotorGrafoCache motorCache;
    private final String archivo;   // vacío ⇒ persistencia desactivada (no-op)

    /** Claves en la caché en el último guardado/carga: evita reescribir el archivo sin cambios. */
    private int clavesUltimoGuardado = 0;

    @Autowired
    public SkeletonCacheStore(DataLoader dataLoader, MotorGrafoCache motorCache,
                              PlanificadorProperties props) {
        this(dataLoader, motorCache, props.getCache().getSkeletonFile());
    }

    /** Constructor directo (tests / instancia no-op con archivo vacío). */
    SkeletonCacheStore(DataLoader dataLoader, MotorGrafoCache motorCache, String archivo) {
        this.dataLoader = dataLoader;
        this.motorCache = motorCache;
        this.archivo = archivo == null ? "" : archivo.trim();
    }

    private boolean desactivado() {
        return archivo.isEmpty();
    }

    private Path path() {
        return Path.of(archivo);
    }

    /**
     * Carga la caché persistida al arrancar (DataLoader ya cargó el dataset en su @PostConstruct).
     * Si no hay archivo, la huella no coincide o está corrupto, arranca con caché vacía como hoy.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void cargarAlArranque() {
        if (desactivado()) return;
        Map<Long, List<int[]>> cargada = leerSiCoincide(path(), huellaDataset());
        if (cargada.isEmpty()) return;
        motorCache.skeletonCache().putAll(cargada);
        clavesUltimoGuardado = motorCache.skeletonCache().size();
        log.info("Caché de esqueletos cargada desde {}: {} claves (pre-warm de corridas previas reutilizado).",
                archivo, cargada.size());
    }

    /**
     * Persiste la caché si ganó claves desde el último guardado (si no, no toca el disco).
     * Best-effort: nunca lanza — un fallo de IO deja la caché solo en RAM, como hoy.
     * También corre en {@code @PreDestroy} para capturar lo aprendido ante un stop ordenado.
     */
    @PreDestroy
    public synchronized void guardarSiCrecio() {
        if (desactivado()) return;
        int claves = motorCache.skeletonCache().size();
        if (claves <= clavesUltimoGuardado) return;
        try {
            escribir(path(), huellaDataset(), motorCache.skeletonCache());
            clavesUltimoGuardado = claves;
            log.info("Caché de esqueletos persistida en {} ({} claves).", archivo, claves);
        } catch (Exception ex) {
            log.warn("No se pudo persistir la caché de esqueletos en {}: {}", archivo, ex.getMessage());
        }
    }

    /** Borra el archivo. Llamar en la ingesta: el dataset nuevo invalida los esqueletos guardados. */
    public synchronized void borrar() {
        if (desactivado()) return;
        clavesUltimoGuardado = 0;
        try {
            Files.deleteIfExists(path());
        } catch (IOException ex) {
            log.warn("No se pudo borrar {} (inofensivo: la huella lo descartará al cargar): {}",
                    archivo, ex.getMessage());
        }
    }

    /**
     * Huella del dataset: SHA-256 sobre TODOS los campos de aeropuertos y vuelos (en el orden
     * estable de DataLoader). Cualquier cambio en la malla ⇒ huella distinta ⇒ archivo descartado.
     */
    String huellaDataset() {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);   // imposible en un JRE estándar
        }
        List<Aeropuerto> aeropuertos = dataLoader != null ? dataLoader.getAeropuertos() : null;
        if (aeropuertos != null) {
            for (Aeropuerto a : aeropuertos) {
                md.update((a.getCodigo() + "|" + a.getOffsetHorario() + "|" + a.getCapacidad() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        List<Vuelo> vuelos = dataLoader != null ? dataLoader.getVuelos() : null;
        if (vuelos != null) {
            for (Vuelo v : vuelos) {
                md.update((v.getOrigen() + "|" + v.getDestino() + "|" + v.getFechaHoraSalida() + "|"
                        + v.getFechaHoraLlegada() + "|" + v.getCapacidad() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    // ── IO de archivo (estático y puro: testeable sin Spring ni BD) ─────────────────────────────

    /**
     * Escribe la caché con escritura atómica (tmp en el mismo directorio + move): o queda el archivo
     * completo con huella válida, o queda el anterior. Formato: MAGIC, VERSION, huella (UTF), nº de
     * claves y por clave {@code long} + nº de esqueletos + ({@code len} + ints de edge-idx).
     */
    static void escribir(Path destino, String huella, Map<Long, List<int[]>> cache) throws IOException {
        Path dir = destino.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "skeleton-cache", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(huella);
                out.writeInt(cache.size());
                for (Map.Entry<Long, List<int[]>> e : cache.entrySet()) {
                    out.writeLong(e.getKey());
                    List<int[]> esqueletos = e.getValue();
                    out.writeInt(esqueletos.size());
                    for (int[] sk : esqueletos) {
                        out.writeInt(sk.length);
                        for (int idx : sk) out.writeInt(idx);
                    }
                }
            }
            try {
                Files.move(tmp, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Lee la caché si el archivo existe, el formato es el esperado y la huella coincide con la del
     * dataset actual; en cualquier otro caso (incluido corrupto/truncado) devuelve vacío sin lanzar.
     */
    static Map<Long, List<int[]>> leerSiCoincide(Path origen, String huella) {
        if (!Files.isRegularFile(origen)) return Map.of();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(origen)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                log.warn("Caché de esqueletos {} con formato/versión desconocidos: se ignora.", origen);
                return Map.of();
            }
            if (!huella.equals(in.readUTF())) {
                log.info("Caché de esqueletos {} descartada: huella de dataset distinta (¿ingesta de por medio?).",
                        origen);
                return Map.of();
            }
            int nClaves = in.readInt();
            if (nClaves < 0) throw new IOException("nº de claves negativo: " + nClaves);
            Map<Long, List<int[]>> out = new HashMap<>(Math.max(16, nClaves * 2));
            for (int i = 0; i < nClaves; i++) {
                long clave = in.readLong();
                int nSk = in.readInt();
                if (nSk < 0 || nSk > MAX_SKELETONS_LEIDOS) {
                    throw new IOException("nº de esqueletos fuera de rango: " + nSk);
                }
                List<int[]> esqueletos = new ArrayList<>(nSk);
                for (int j = 0; j < nSk; j++) {
                    int len = in.readInt();
                    if (len < 0 || len > MAX_TRAMOS_LEIDOS) {
                        throw new IOException("longitud de esqueleto fuera de rango: " + len);
                    }
                    int[] sk = new int[len];
                    for (int t = 0; t < len; t++) sk[t] = in.readInt();
                    esqueletos.add(sk);
                }
                out.put(clave, esqueletos);
            }
            return out;
        } catch (IOException ex) {
            // EOFException (archivo truncado) llega sin mensaje: mostrar al menos el tipo.
            String motivo = ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            log.warn("Caché de esqueletos {} ilegible ({}): se ignora y se arranca con caché vacía.",
                    origen, motivo);
            return Map.of();
        }
    }
}
