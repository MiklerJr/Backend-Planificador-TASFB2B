package com.tasfb2b.planificador.servicios.jobs;

import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.dto.datos.AeropuertoAgregado;
import com.tasfb2b.planificador.dto.datos.AltaAeropuertoRequest;
import com.tasfb2b.planificador.dto.jobs.AlertaColapso;
import com.tasfb2b.planificador.dto.vuelos.CancelacionVueloRequest;
import com.tasfb2b.planificador.dto.almacenes.*;
import com.tasfb2b.planificador.dto.jobs.*;
import com.tasfb2b.planificador.dto.simulacion.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.dto.vuelos.VueloCancelado;
import lombok.Data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static com.tasfb2b.planificador.utilidades.FormatoSimulacion.safe;

@Data
public class EstadoJob {
    private final String jobId;
    private final String escenario;
    private final int k;
    public volatile String estado = "encolado";

    public volatile boolean canceladoPorUsuario = false;

    public volatile AlertaColapso alertaColapso;

    public volatile int bloqueActual = 0;
    public volatile int totalBloques = 0;

    public volatile int bloqueWarmup = 0;
    public volatile int totalBloquesWarmup = 0;
    public volatile long taPromedioMs = 0L;

    public volatile SimulacionResponse resultado;

    public volatile List<AsignacionMaleta> estadoInicial;
    public volatile String error;
    public volatile String auditoriaCsv;
    public volatile Path auditoriaCsvPath;
    public volatile Path auditoriaZipPath;
    public volatile int auditoriaFilas;
    public volatile List<LoteEnvio> auditoriaSinRuta;

    public final LocalDateTime inicio = LocalDateTime.now();
    public volatile LocalDateTime fin;

    private static final java.util.concurrent.atomic.AtomicLong SECUENCIA_CREACION =
            new java.util.concurrent.atomic.AtomicLong();
    public final long ordenCreacion = SECUENCIA_CREACION.incrementAndGet();

    public volatile String algoritmo = "alns";

    public volatile long seed = 0L;

    public volatile LocalDateTime fechaInicio;

    public volatile boolean enVivo = false;

    public volatile LocalDateTime ventanaInicioUtc;
    public volatile LocalDateTime ventanaFinUtc;

    public void registrarVentanaSimulada(LocalDateTime scInicio, LocalDateTime scFin) {
        if (ventanaInicioUtc == null) ventanaInicioUtc = scInicio;
        ventanaFinUtc = scFin;
    }

    public volatile Long primerBloqueRealMs;
    public volatile Long finRealMs;

    public Long getDuracionRealMs() {
        Long inicioReal = primerBloqueRealMs;
        if (inicioReal == null) return null;
        Long finReal = finRealMs;
        return (finReal != null ? finReal : System.currentTimeMillis()) - inicioReal;
    }

    public volatile Integer saMin;
    public volatile Integer taSegundos;
    public volatile Integer dias;
    public volatile boolean procesamientoPrevio = false;
    public volatile Double umbralColapso;

    private final List<BloqueSimulacion> bloquesParciales =
            new CopyOnWriteArrayList<>();

    private volatile int baseBloquesPurgados = 0;
    private final Object bloquesLock = new Object();

    private volatile int maxBloquesConAsignaciones = 500;

    /**
     * Tope de asignaciones retenidas en el buffer (0 = sin tope). Es el presupuesto que de verdad
     * manda sobre la RAM: al final del dataset un bloque de 12 h trae ~11.800 asignaciones, así que
     * contar bloques dice poco. {@link #maxBloquesConAsignaciones} sigue vigente como tope
     * secundario (nunca se retienen más bloques que ese número) y siempre queda al menos el último.
     */
    private volatile int maxAsignacionesBuffer = 0;

    /** Asignaciones vivas en {@link #bloquesParciales} (mantenido por deltas al publicar/purgar). */
    private int asignacionesRetenidas = 0;

    private final Map<String, VueloUsadoAcc> vuelosUsadosAcum = new LinkedHashMap<>();

    public volatile Metricas metricasSnapshot;

    public void setMaxBloquesConAsignaciones(int n) { if (n > 0) this.maxBloquesConAsignaciones = n; }

    public int getMaxBloquesConAsignaciones() { return maxBloquesConAsignaciones; }

    public void setMaxAsignacionesBuffer(int n) { if (n >= 0) this.maxAsignacionesBuffer = n; }

    public int getMaxAsignacionesBuffer() { return maxAsignacionesBuffer; }

    public int asignacionesRetenidas() {
        synchronized (bloquesLock) { return asignacionesRetenidas; }
    }

    /**
     * ¿El buffer excede su presupuesto y hay que soltar el bloque más viejo? Manda el tope de
     * bloques; si ese se respeta, manda el de asignaciones, que nunca purga el último bloque.
     */
    private static boolean debePurgarBloque(int bloques, int asignaciones, int maxBloques, int maxAsignaciones) {
        if (bloques > maxBloques) return true;
        return maxAsignaciones > 0 && asignaciones > maxAsignaciones && bloques > 1;
    }

    private static int cuentaAsignaciones(BloqueSimulacion bloque) {
        return bloque == null || bloque.getAsignaciones() == null ? 0 : bloque.getAsignaciones().size();
    }

    public EstadoJob(String jobId, String escenario, int k) {
        this.jobId     = jobId;
        this.escenario = escenario;
        this.k         = k;
    }

    public double getProgreso() {
        return totalBloques == 0 ? 0.0 : (double) bloqueActual / totalBloques;
    }

    public double getProgresoWarmup() {
        return totalBloquesWarmup == 0 ? 0.0 : (double) bloqueWarmup / totalBloquesWarmup;
    }

    private final Queue<CancelacionVueloRequest> cancelacionesVueloPendientes =
            new ConcurrentLinkedQueue<>();

    public boolean encolarCancelacionVuelo(CancelacionVueloRequest orden) {
        if (orden == null) return false;
        if (cancelacionesVueloPendientes.contains(orden)) return false;   // anti doble-click
        return cancelacionesVueloPendientes.add(orden);
    }

    private final List<VueloCancelado> vuelosCancelados = new CopyOnWriteArrayList<>();

    private final List<CancelacionVueloRequest> cancelacionesNoAplicadas = new CopyOnWriteArrayList<>();

    public Queue<CancelacionVueloRequest> getCancelacionesVueloPendientes() {
        return cancelacionesVueloPendientes;
    }

    private final Queue<InyeccionEnviosRequest.Item> inyeccionesPendientes = new ConcurrentLinkedQueue<>();

    public void encolarInyeccion(InyeccionEnviosRequest.Item it) {
        if (it != null) inyeccionesPendientes.add(it);
    }

    public Queue<InyeccionEnviosRequest.Item> getInyeccionesPendientes() {
        return inyeccionesPendientes;
    }

    private final Queue<AltaVueloRequest> altasVueloPendientes = new ConcurrentLinkedQueue<>();

    public boolean encolarAltaVuelo(AltaVueloRequest alta) {
        if (alta == null) return false;
        if (altasVueloPendientes.contains(alta)) return false;   // anti doble-click
        return altasVueloPendientes.add(alta);
    }

    public Queue<AltaVueloRequest> getAltasVueloPendientes() {
        return altasVueloPendientes;
    }

    private final List<VueloAgregado> vuelosAgregados = new CopyOnWriteArrayList<>();

    private final List<VueloAgregado> altasVueloNoAplicadas = new CopyOnWriteArrayList<>();

    private final Queue<AltaAeropuertoRequest> altasAeropuertoPendientes = new ConcurrentLinkedQueue<>();

    public boolean encolarAltaAeropuerto(AltaAeropuertoRequest alta) {
        if (alta == null) return false;
        if (altasAeropuertoPendientes.contains(alta)) return false;   // anti doble-click
        return altasAeropuertoPendientes.add(alta);
    }

    public Queue<AltaAeropuertoRequest> getAltasAeropuertoPendientes() {
        return altasAeropuertoPendientes;
    }

    private final List<AeropuertoAgregado> aeropuertosAgregados = new CopyOnWriteArrayList<>();

    private final List<AeropuertoAgregado> altasAeropuertoNoAplicadas = new CopyOnWriteArrayList<>();

    private final List<EnvioInyectadoInfo> enviosInyectados = new CopyOnWriteArrayList<>();

    private final Map<String, AsignacionMaleta> rutasSinteticas = new ConcurrentHashMap<>();

    private final List<List<OcupacionAlmacenSlot>> seriesAlmacenes = new ArrayList<>();
    private int baseSeriesPurgadas = 0;
    private final Object seriesLock = new Object();

    public void publicarBloque(BloqueSimulacion bloque) {
        if (bloque == null) return;
        if (primerBloqueRealMs == null) primerBloqueRealMs = System.currentTimeMillis();
        acumularVuelosUsados(bloque);
        indexarRutasSinteticas(bloque);
        synchronized (bloquesLock) {
            bloquesParciales.add(bloque);
            asignacionesRetenidas += cuentaAsignaciones(bloque);
            while (debePurgarBloque(bloquesParciales.size(), asignacionesRetenidas,
                    maxBloquesConAsignaciones, maxAsignacionesBuffer)) {
                asignacionesRetenidas -= cuentaAsignaciones(bloquesParciales.remove(0));
                baseBloquesPurgados++;
            }
        }
        purgarVuelosUsadosViejos();
    }

    private void purgarVuelosUsadosViejos() {
        int corte = primerBloqueDisponible();
        if (corte <= 0) return;
        synchronized (vuelosUsadosAcum) {
            Iterator<VueloUsadoAcc> it = vuelosUsadosAcum.values().iterator();
            while (it.hasNext()) {
                if (it.next().bloqueIdx < corte) it.remove(); else break;
            }
        }
    }

    public void publicarSerieAlmacenes(List<OcupacionAlmacenSlot> serie) {
        synchronized (seriesLock) {
            seriesAlmacenes.add(serie != null ? serie : List.of());
            while (seriesAlmacenes.size() > maxBloquesConAsignaciones) {
                seriesAlmacenes.remove(0);
                baseSeriesPurgadas++;
            }
        }
    }

    public List<List<OcupacionAlmacenSlot>> seriesDesde(int desde) {
        synchronized (seriesLock) {
            if (desde < baseSeriesPurgadas) desde = baseSeriesPurgadas;
            int rel = desde - baseSeriesPurgadas;
            if (rel < 0 || rel >= seriesAlmacenes.size()) return List.of();
            return List.copyOf(seriesAlmacenes.subList(rel, seriesAlmacenes.size()));
        }
    }

    public List<List<OcupacionAlmacenSlot>> seriesDesdeExacto(int desde) {
        synchronized (seriesLock) {
            if (Math.max(desde, 0) < baseSeriesPurgadas) return List.of();
            return seriesDesde(desde);
        }
    }

    public int primeraSerieDisponible() {
        synchronized (seriesLock) {
            return baseSeriesPurgadas;
        }
    }

    public int seriesPublicadas() {
        synchronized (seriesLock) {
            return baseSeriesPurgadas + seriesAlmacenes.size();
        }
    }

    public List<BloqueSimulacion> bloquesDesde(int desde) {
        synchronized (bloquesLock) {
            if (desde < baseBloquesPurgados) desde = baseBloquesPurgados;
            int rel = desde - baseBloquesPurgados;
            if (rel < 0 || rel >= bloquesParciales.size()) return List.of();
            return List.copyOf(bloquesParciales.subList(rel, bloquesParciales.size()));
        }
    }


    public List<BloqueSimulacion> bloquesDesdeExacto(int desde) {
        synchronized (bloquesLock) {
            if (Math.max(desde, 0) < baseBloquesPurgados) return List.of();
            return bloquesDesde(desde);
        }
    }

    public int primerBloqueDisponible() {
        synchronized (bloquesLock) {
            return baseBloquesPurgados;
        }
    }

    public int bloquesPublicados() {
        synchronized (bloquesLock) {
            return baseBloquesPurgados + bloquesParciales.size();
        }
    }

    public BloqueSimulacion ultimoBloque() {
        synchronized (bloquesLock) {
            int n = bloquesParciales.size();
            return n == 0 ? null : bloquesParciales.get(n - 1);
        }
    }

    private void acumularVuelosUsados(BloqueSimulacion bloque) {
        if (bloque.getAsignaciones() == null) return;
        synchronized (vuelosUsadosAcum) {
            List<AsignacionMaleta> asigs = bloque.getAsignaciones();
            for (int idx = 0; idx < asigs.size(); idx++) {
                AsignacionMaleta a = asigs.get(idx);
                if (a == null || !a.isEnrutada() || a.getTramos() == null || a.getTramos().isEmpty()) continue;

                String envioId = safe(a.getBatchId());
                String envioKey = !envioId.isEmpty()
                        ? envioId
                        : "sin-id:" + bloque.getBloqueIdx() + ":" + idx;

                for (TramoRuta tramo : a.getTramos()) {
                    if (tramo == null) continue;
                    String vueloId = safe(tramo.getVueloId());
                    String salida = safe(tramo.getSalidaUtc());
                    String llegada = safe(tramo.getLlegadaUtc());
                    String key = bloque.getBloqueIdx() + "|" + vueloId + "|" + salida;

                    VueloUsadoAcc vuelo = vuelosUsadosAcum.computeIfAbsent(key, k -> {
                        VueloUsadoAcc nuevo = new VueloUsadoAcc(bloque.getBloqueIdx());
                        nuevo.row.setFlightKey(vueloId + "|" + salida);
                        nuevo.row.setBloqueIdx(bloque.getBloqueIdx());
                        nuevo.row.setHoraInicio(bloque.getHoraInicio());
                        nuevo.row.setHoraFin(bloque.getHoraFin());
                        nuevo.row.setVueloId(vueloId);
                        nuevo.row.setOrigen(safe(tramo.getOrigen()));
                        nuevo.row.setDestino(safe(tramo.getDestino()));
                        nuevo.row.setFechaSalida(salida);
                        nuevo.row.setFechaLlegada(llegada);
                        return nuevo;
                    });
                    if (vuelo.envioKeys.add(envioKey)) {
                        vuelo.row.setCantidadMaletas(vuelo.row.getCantidadMaletas() + a.getCantidad());
                        if (!envioId.isEmpty()) vuelo.envioIds.add(envioId);
                    }
                }
            }
        }
    }

    private void indexarRutasSinteticas(BloqueSimulacion bloque) {
        if (bloque.getAsignaciones() == null) return;
        for (AsignacionMaleta a : bloque.getAsignaciones()) {
            if (a == null || !a.isEnrutada()) continue;
            String id = a.getBatchId();
            if (id != null && id.startsWith("INV-")) rutasSinteticas.put(id, a);
        }
    }

    public AsignacionMaleta getRutaSintetica(String idEnvio) {
        return idEnvio == null ? null : rutasSinteticas.get(idEnvio);
    }

    public List<AsignacionMaleta> getRutasSinteticasFamilia(String idEnvio) {
        if (idEnvio == null) return List.of();
        List<AsignacionMaleta> out = new ArrayList<>();
        for (Map.Entry<String, AsignacionMaleta> e : rutasSinteticas.entrySet()) {
            String k = e.getKey();
            if (k.equals(idEnvio)
                    || com.tasfb2b.planificador.utilidades.FragmentadorEnvios.idPadreDe(k).equals(idEnvio)) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    public int vuelosUsadosAcumSize() {
        synchronized (vuelosUsadosAcum) { return vuelosUsadosAcum.size(); }
    }

    public void borrarZip() {
        Path z = auditoriaZipPath;
        if (z != null) {
            auditoriaZipPath = null;
            try { Files.deleteIfExists(z); } catch (Exception ignored) { /* best-effort */ }
        }
    }

    public void liberarPesados() {
        synchronized (bloquesLock) { bloquesParciales.clear(); asignacionesRetenidas = 0; }
        synchronized (vuelosUsadosAcum) { vuelosUsadosAcum.clear(); }
        synchronized (seriesLock) { seriesAlmacenes.clear(); }
        rutasSinteticas.clear();
        estadoInicial = null;
        resultado = null;
        auditoriaSinRuta = null;
    }

    public List<VuelosUsadosResponse.VueloUsado> vuelosUsadosDesde(int desde) {
        synchronized (vuelosUsadosAcum) {
            return vuelosUsadosAcum.values().stream()
                    .filter(v -> v.bloqueIdx >= desde)
                    .map(VueloUsadoAcc::toDto)
                    .sorted(Comparator.comparingInt(VuelosUsadosResponse.VueloUsado::getBloqueIdx)
                            .thenComparing(VuelosUsadosResponse.VueloUsado::getFechaSalida)
                            .thenComparing(VuelosUsadosResponse.VueloUsado::getVueloId))
                    .collect(Collectors.toList());
        }
    }

    static final class VueloUsadoAcc {
        final int bloqueIdx;
        final VuelosUsadosResponse.VueloUsado row = new VuelosUsadosResponse.VueloUsado();
        final Set<String> envioKeys = new LinkedHashSet<>();
        final List<String> envioIds = new ArrayList<>();

        VueloUsadoAcc(int bloqueIdx) { this.bloqueIdx = bloqueIdx; }

        VuelosUsadosResponse.VueloUsado toDto() {
            row.setCantidadEnvios(envioKeys.size());
            row.setEnvioIds(List.copyOf(envioIds));
            return row;
        }
    }
}
