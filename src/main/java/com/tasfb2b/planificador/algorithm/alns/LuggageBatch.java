package com.tasfb2b.planificador.algorithm.alns;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.tasfb2b.planificador.algorithm.grafo.Edge;

public class LuggageBatch {
    private String id;
    private int quantity;
    private int slaLimitHours;

    private String originCode;
    private String destCode;
    private LocalDateTime readyTime;

    /** Identificador del cliente que originó el envío (puede ser null si no se propaga). */
    private Integer clienteId;

    /**
     * Marca un envío inyectado EN VIVO por el operador (id sintético "INV-..."). Su ruta NO se
     * persiste en {@code ruta_asignada} (id_envio inexistente en la tabla {@code envio} ⇒ rompería la
     * FK y la transacción del bloque); ver {@code PersistenciaSolucionService.persistirBloque}. El
     * hecho del envío se registra aparte en {@code envio_inyectado}.
     */
    private boolean sintetico;

    private List<Edge> assignedRoute;
    private List<Long> assignedDepartures; // epoch-minutes, paralelo a assignedRoute
    private boolean cumpleSLA;

    // ── Fase 2: re-enrutamiento desde la posición física tras una cancelación ──
    /** Tramos ya volados (o en vuelo) que se preservan al re-enrutar desde una escala. Null/vacío
     *  en el caso normal. La ruta REAL del envío = prefijoFijo + assignedRoute (sufijo). */
    private List<Edge> prefijoFijo;
    /** Salidas (epoch-min UTC) paralelas a {@link #prefijoFijo}. */
    private List<Long> prefijoFijoDepartures;
    /** Aeropuerto desde el que se (re)enruta el sufijo (la escala actual). Default = originCode. */
    private String currentOriginCode;
    /** readyTime efectivo del sufijo (llegada a la escala). Default = readyTime original. */
    private LocalDateTime currentReadyTime;

    public LuggageBatch(String id, int quantity, int slaLimitHours,
                        String originCode, String destCode, LocalDateTime readyTime) {
        this.id            = id;
        this.quantity      = quantity;
        this.slaLimitHours = slaLimitHours;
        this.originCode    = originCode;
        this.destCode      = destCode;
        this.readyTime     = readyTime;
        this.assignedRoute = new ArrayList<>();
        this.cumpleSLA     = false;
        this.currentOriginCode = originCode;   // sin prefijo: posición = origen real
        this.currentReadyTime  = readyTime;
    }

    public void setAssignedRoute(List<Edge> route) {
        this.assignedRoute = route;
    }

    public void setAssignedDepartures(List<Long> deps) {
        this.assignedDepartures = deps;
    }

    public void clearRoute() {
        this.assignedRoute = new ArrayList<>();
        this.assignedDepartures = null;
    }

    // Tiempo de tránsito real usando los departures reales si están disponibles.
    // Usado por AlnsSolution.calculateCost() como función objetivo.
    public double getTotalTransitTimeMins() {
        if (assignedRoute == null || assignedRoute.isEmpty()) return 10000.0;

        if (assignedDepartures != null && !assignedDepartures.isEmpty()) {
            long readyMin = toEpochMin(readyTime);
            int  lastIdx  = assignedRoute.size() - 1;
            long arrLast  = assignedDepartures.get(lastIdx)
                          + assignedRoute.get(lastIdx).durationMinutes;
            return arrLast - readyMin;
        }
        // fallback: usa la hora estática de la arista
        return java.time.Duration.between(readyTime,
                assignedRoute.get(assignedRoute.size() - 1).arrivalTime).toMinutes();
    }

    /**
     * Devuelve el ratio de holgura SLA: {@code (slaLimit - transitTime) / slaLimit}.
     * <ul>
     *   <li>1.0 = entrega instantánea (todo el SLA disponible)</li>
     *   <li>0.0 = al límite del SLA</li>
     *   <li>negativo = ya tarde (incumplió SLA)</li>
     * </ul>
     * Usado por el {@link BacklogManager} para detectar batches "próximos a tardar"
     * que ameriten replanificación preventiva (umbral típico 0.10).
     */
    public double getSlaSlackRatio() {
        if (assignedRoute == null || assignedRoute.isEmpty()) return -1.0;
        double slaMin = slaLimitHours * 60.0;
        double transitMin = getTotalTransitTimeMins();
        return (slaMin - transitMin) / slaMin;
    }

    // ── Fase 2: prefijo fijo / posición actual ────────────────────────────────
    public boolean tienePrefijo() {
        return prefijoFijo != null && !prefijoFijo.isEmpty();
    }

    /** Ruta REAL del envío: prefijo volado + sufijo asignado. */
    public List<Edge> getRutaCompleta() {
        if (!tienePrefijo()) return assignedRoute;
        List<Edge> full = new ArrayList<>(prefijoFijo.size()
                + (assignedRoute != null ? assignedRoute.size() : 0));
        full.addAll(prefijoFijo);
        if (assignedRoute != null) full.addAll(assignedRoute);
        return full;
    }

    /** Salidas (epoch-min UTC) paralelas a {@link #getRutaCompleta()}. */
    public List<Long> getDeparturesCompletas() {
        if (!tienePrefijo()) return assignedDepartures;
        List<Long> full = new ArrayList<>(prefijoFijoDepartures.size()
                + (assignedDepartures != null ? assignedDepartures.size() : 0));
        full.addAll(prefijoFijoDepartures);
        if (assignedDepartures != null) full.addAll(assignedDepartures);
        return full;
    }

    /** Aeropuerto desde el que se (re)enruta: la escala actual si hay prefijo, si no el origen. */
    public String origenEfectivo() {
        return tienePrefijo() ? currentOriginCode : originCode;
    }

    /** readyTime efectivo: la llegada a la escala si hay prefijo, si no el readyTime original. */
    public LocalDateTime readyEfectivo() {
        return tienePrefijo() ? currentReadyTime : readyTime;
    }

    public List<Edge> getPrefijoFijo()                 { return prefijoFijo; }
    public void setPrefijoFijo(List<Edge> p)           { this.prefijoFijo = p; }
    public List<Long> getPrefijoFijoDepartures()       { return prefijoFijoDepartures; }
    public void setPrefijoFijoDepartures(List<Long> d) { this.prefijoFijoDepartures = d; }
    public String getCurrentOriginCode()               { return currentOriginCode; }
    public void setCurrentOriginCode(String c)         { this.currentOriginCode = c; }
    public LocalDateTime getCurrentReadyTime()         { return currentReadyTime; }
    public void setCurrentReadyTime(LocalDateTime t)   { this.currentReadyTime = t; }

    public LuggageBatch cloneBatch() {
        LuggageBatch clone = new LuggageBatch(id, quantity, slaLimitHours,
                                               originCode, destCode, readyTime);
        clone.setClienteId(this.clienteId);
        clone.setSintetico(this.sintetico);
        clone.setAssignedRoute(new ArrayList<>(this.assignedRoute));
        clone.setAssignedDepartures(
                assignedDepartures != null ? new ArrayList<>(assignedDepartures) : null);
        clone.setCumpleSLA(this.cumpleSLA);
        // Fase 2: preservar la posición actual y el prefijo (el ACO no clona, pero por robustez).
        clone.setCurrentOriginCode(this.currentOriginCode);
        clone.setCurrentReadyTime(this.currentReadyTime);
        clone.setPrefijoFijo(this.prefijoFijo != null ? new ArrayList<>(this.prefijoFijo) : null);
        clone.setPrefijoFijoDepartures(
                this.prefijoFijoDepartures != null ? new ArrayList<>(this.prefijoFijoDepartures) : null);
        return clone;
    }

    private static long toEpochMin(LocalDateTime dt) {
        return dt.toLocalDate().toEpochDay() * 1440L + dt.getHour() * 60L + dt.getMinute();
    }

    public String getId()                     { return id; }
    public int getQuantity()                  { return quantity; }
    public int getSlaLimitHours()             { return slaLimitHours; }
    public String getOriginCode()             { return originCode; }
    public String getDestCode()               { return destCode; }
    public LocalDateTime getReadyTime()       { return readyTime; }
    public List<Edge> getAssignedRoute()      { return assignedRoute; }
    public List<Long> getAssignedDepartures() { return assignedDepartures; }
    public boolean isCumpleSLA()              { return cumpleSLA; }
    public void setCumpleSLA(boolean v)       { this.cumpleSLA = v; }
    public Integer getClienteId()             { return clienteId; }
    public void setClienteId(Integer id)      { this.clienteId = id; }
    public boolean isSintetico()              { return sintetico; }
    public void setSintetico(boolean v)       { this.sintetico = v; }
}
