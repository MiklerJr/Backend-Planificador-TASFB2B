package com.tasfb2b.planificador.algorithm.alns;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.tasfb2b.planificador.algorithm.aco.Edge;

public class LuggageBatch {
    private String id;
    private int quantity;
    private int slaLimitHours;

    private String originCode;
    private String destCode;
    private LocalDateTime readyTime;

    private List<Edge> assignedRoute;
    private List<Long> assignedDepartures; // epoch-minutes, paralelo a assignedRoute
    private boolean cumpleSLA;

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

    public LuggageBatch cloneBatch() {
        LuggageBatch clone = new LuggageBatch(id, quantity, slaLimitHours,
                                               originCode, destCode, readyTime);
        clone.setAssignedRoute(new ArrayList<>(this.assignedRoute));
        clone.setAssignedDepartures(
                assignedDepartures != null ? new ArrayList<>(assignedDepartures) : null);
        clone.setCumpleSLA(this.cumpleSLA);
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
}
