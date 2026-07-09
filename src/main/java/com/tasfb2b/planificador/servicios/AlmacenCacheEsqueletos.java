package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
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

@Slf4j
@Component
public class AlmacenCacheEsqueletos {

    static final int MAGIC = 0x54534B31;   // "TSK1"
    static final int VERSION = 1;
    private static final int MAX_SKELETONS_LEIDOS = 64;
    private static final int MAX_TRAMOS_LEIDOS = 4096;

    private final CargadorDatos cargadorDatos;
    private final MotorGrafoCache motorCache;
    private final String archivo;   // vacío ⇒ persistencia desactivada (no-op)

    // Escala mínima y recojo en destino (min): entran en la huella para que cambiarlos invalide la caché
    // persistida (los esqueletos son topológicos, pero su factibilidad SLA depende de estos tiempos).
    private int tiempoMinEscala = 10;
    private int tiempoRecojoDestino = 15;

    private int clavesUltimoGuardado = 0;

    @Autowired
    public AlmacenCacheEsqueletos(CargadorDatos cargadorDatos, MotorGrafoCache motorCache,
                              PlanificadorProperties props) {
        this(cargadorDatos, motorCache, props.getCache().getSkeletonFile());
        this.tiempoMinEscala = props.getOperativo().getTiempoMinEscalaMinutos();
        this.tiempoRecojoDestino = props.getOperativo().getTiempoRecojoDestinoMinutos();
    }

    AlmacenCacheEsqueletos(CargadorDatos cargadorDatos, MotorGrafoCache motorCache, String archivo) {
        this.cargadorDatos = cargadorDatos;
        this.motorCache = motorCache;
        this.archivo = archivo == null ? "" : archivo.trim();
    }

    private boolean desactivado() {
        return archivo.isEmpty();
    }

    private Path path() {
        return Path.of(archivo);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void cargarAlArranque() {
        if (desactivado()) return;
        Map<Long, List<int[]>> cargada = leerSiCoincide(path(), huellaDataset());
        if (cargada.isEmpty()) return;
        motorCache.cacheEsqueletos().putAll(cargada);
        clavesUltimoGuardado = motorCache.cacheEsqueletos().size();
        log.info("Caché de esqueletos cargada desde {}: {} claves (pre-warm de corridas previas reutilizado).",
                archivo, cargada.size());
    }

    @PreDestroy
    public synchronized void guardarSiCrecio() {
        if (desactivado()) return;
        int claves = motorCache.cacheEsqueletos().size();
        if (claves <= clavesUltimoGuardado) return;
        try {
            // El guardado corre al FIN de cada corrida, cuando las altas EN CALIENTE aún viven (se
            // revierten al iniciar la siguiente): se filtran los esqueletos que referencien aristas
            // efímeras (índices >= nº de vuelos baseline) para que el archivo sea siempre el baseline.
            escribir(path(), huellaDataset(), sinEsqueletosEfimeros(motorCache.cacheEsqueletos()));
            clavesUltimoGuardado = claves;
            log.info("Caché de esqueletos persistida en {} ({} claves).", archivo, claves);
        } catch (Exception ex) {
            log.warn("No se pudo persistir la caché de esqueletos en {}: {}", archivo, ex.getMessage());
        }
    }

    /** Copia de la caché sin los esqueletos que usan aristas efímeras (índice >= vuelos baseline). */
    Map<Long, List<int[]>> sinEsqueletosEfimeros(Map<Long, List<int[]>> cache) {
        List<Vuelo> vuelos = cargadorDatos != null ? cargadorDatos.getVuelos() : null;
        if (vuelos == null) return cache;
        int base = 0;
        boolean hayEfimeros = false;
        for (Vuelo v : vuelos) {
            if (v.isEfimero()) hayEfimeros = true;
            else base++;
        }
        if (!hayEfimeros) return cache;
        Map<Long, List<int[]>> filtrada = new HashMap<>(Math.max(16, cache.size() * 2));
        for (Map.Entry<Long, List<int[]>> e : cache.entrySet()) {
            List<int[]> limpios = new ArrayList<>(e.getValue().size());
            for (int[] sk : e.getValue()) {
                boolean usaEfimera = false;
                for (int idx : sk) if (idx >= base) { usaEfimera = true; break; }
                if (!usaEfimera) limpios.add(sk);
            }
            if (!limpios.isEmpty()) filtrada.put(e.getKey(), limpios);
        }
        return filtrada;
    }

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

    String huellaDataset() {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);   // imposible en un JRE estándar
        }
        // La escala mínima SÍ entra (cambia la factibilidad de los esqueletos). Las capacidades NO:
        // los esqueletos son topológicos (índices de aristas; la capacidad se valida en runtime contra la
        // ocupación) y son mutables por corrida (ConfiguracionCapacidadesService) — incluirlas invalidaría
        // la caché tras cada PUT/reset.
        md.update(("escala|" + tiempoMinEscala + "|recojo|" + tiempoRecojoDestino + "\n")
                .getBytes(StandardCharsets.UTF_8));
        // Las altas EN CALIENTE (efímeras por corrida) tampoco entran: la huella es siempre la del
        // dataset baseline, para que un guardado con altas vivas no invalide el archivo al reiniciar.
        List<Aeropuerto> aeropuertos = cargadorDatos != null ? cargadorDatos.getAeropuertos() : null;
        if (aeropuertos != null) {
            for (Aeropuerto a : aeropuertos) {
                if (a.isEfimero()) continue;
                md.update((a.getCodigo() + "|" + a.getOffsetHorario() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        List<Vuelo> vuelos = cargadorDatos != null ? cargadorDatos.getVuelos() : null;
        if (vuelos != null) {
            for (Vuelo v : vuelos) {
                if (v.isEfimero()) continue;
                md.update((v.getOrigen() + "|" + v.getDestino() + "|" + v.getFechaHoraSalida() + "|"
                        + v.getFechaHoraLlegada() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

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
            String motivo = ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            log.warn("Caché de esqueletos {} ilegible ({}): se ignora y se arranca con caché vacía.",
                    origen, motivo);
            return Map.of();
        }
    }
}
