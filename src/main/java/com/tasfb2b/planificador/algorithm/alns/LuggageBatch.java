package com.tasfb2b.planificador.algorithm.alns;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.tasfb2b.planificador.algorithm.aco.Edge;

public class LuggageBatch {
    private String id;
    private int quantity;
    private int slaLimitHours;

    // Nuevos campos agregados
    private String originCode;
    private String destCode;
    private LocalDateTime readyTime;

    private List<Edge> assignedRoute;

    // Constructor actualizado para recibir los 6 parámetros
    public LuggageBatch(String id, int quantity, int slaLimitHours, String originCode, String destCode, LocalDateTime readyTime) {
        this.id = id;
        this.quantity = quantity;
        this.slaLimitHours = slaLimitHours;
        this.originCode = originCode;
        this.destCode = destCode;
        this.readyTime = readyTime;
        this.assignedRoute = new ArrayList<>();
    }

    public void setAssignedRoute(List<Edge> route) {
        this.assignedRoute = route;
    }

    public void clearRoute() {
        this.assignedRoute.clear();
    }

    public double getTotalTransitTimeMins() {
        if (assignedRoute == null || assignedRoute.isEmpty()) {
            // Penalización alta si no tiene ruta (para que el algoritmo prefiera asignarlas)
            return 10000.0;
        }

        // El tiempo total es desde que la maleta está lista hasta que llega el último vuelo
        LocalDateTime arrivalTime = assignedRoute.get(assignedRoute.size() - 1).arrivalTime;
        return Duration.between(readyTime, arrivalTime).toMinutes();
    }

    public LuggageBatch cloneBatch() {
        // El clon ahora también copia los nuevos atributos
        LuggageBatch clone = new LuggageBatch(this.id, this.quantity, this.slaLimitHours, this.originCode, this.destCode, this.readyTime);
        clone.setAssignedRoute(new ArrayList<>(this.assignedRoute));
        return clone;
    }

    // Getters necesarios para que el algoritmo pueda leer los datos
    public String getId() { return id; }
    public int getQuantity() { return quantity; }
    public int getSlaLimitHours() { return slaLimitHours; }
    public String getOriginCode() { return originCode; }
    public String getDestCode() { return destCode; }
    public LocalDateTime getReadyTime() { return readyTime; }
    public List<Edge> getAssignedRoute() { return assignedRoute; }
}