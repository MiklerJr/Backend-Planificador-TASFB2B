package com.tasfb2b.planificador.algoritmo.alns;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class GestorBacklog {

    private final Deque<LoteEnvio> sinRuta         = new ArrayDeque<>();
    private final Deque<LoteEnvio> replanificables = new ArrayDeque<>();
    private int     sinRutaDefinitivo = 0;
    private int     picoHistorico     = 0;
    private final int     maxSize;
    private final boolean purgarVencidas;
    private final Consumer<LoteEnvio> onDescarte;

    public GestorBacklog(int maxSize, boolean purgarVencidas) {
        this(maxSize, purgarVencidas, null);
    }

    public GestorBacklog(int maxSize, boolean purgarVencidas, Consumer<LoteEnvio> onDescarte) {
        this.maxSize        = Math.max(0, maxSize);
        this.purgarVencidas = purgarVencidas;
        this.onDescarte     = onDescarte;
    }

    public void addSinRuta(LoteEnvio batch) {
        sinRuta.addLast(batch);
        actualizarPico();
        aplicarTope();
    }

    public void addReplanificable(LoteEnvio batch) {
        replanificables.addLast(batch);
        actualizarPico();
        aplicarTope();
    }

    public List<LoteEnvio> pollPendientes() {
        if (sinRuta.isEmpty() && replanificables.isEmpty()) return List.of();
        List<LoteEnvio> result = new ArrayList<>(sinRuta.size() + replanificables.size());
        result.addAll(sinRuta);
        result.addAll(replanificables);
        sinRuta.clear();
        replanificables.clear();
        return result;
    }

    public List<LoteEnvio> peekPendientes() {
        if (sinRuta.isEmpty() && replanificables.isEmpty()) return List.of();
        List<LoteEnvio> result = new ArrayList<>(sinRuta.size() + replanificables.size());
        result.addAll(sinRuta);
        result.addAll(replanificables);
        return result;
    }

    public List<LoteEnvio> pollPendientes(int max) {
        if (max <= 0) return List.of();
        List<LoteEnvio> result = new ArrayList<>(Math.min(max, size()));
        // Prioridad: sinRuta primero (críticos), luego replanificables.
        while (!sinRuta.isEmpty() && result.size() < max) {
            result.add(sinRuta.pollFirst());
        }
        while (!replanificables.isEmpty() && result.size() < max) {
            result.add(replanificables.pollFirst());
        }
        return result;
    }

    public List<LoteEnvio> pollPendientesUrgentes(int max) {
        int total = sinRuta.size() + replanificables.size();
        if (max <= 0 || max >= total) {
            return pollPendientes();
        }
        List<LoteEnvio> todos = new ArrayList<>(total);
        todos.addAll(sinRuta);
        todos.addAll(replanificables);
        sinRuta.clear();
        replanificables.clear();
        todos.sort(Comparator.comparing(
                b -> b.getReadyTime().plusHours(b.getSlaLimitHours())));
        List<LoteEnvio> out = new ArrayList<>(todos.subList(0, max));
        // Re-encolar los menos urgentes (ya ordenados por deadline) para el próximo bloque.
        for (int i = max; i < todos.size(); i++) {
            sinRuta.addLast(todos.get(i));
        }
        return out;
    }

    public int purgarVencidas(LocalDateTime scNow) {
        if (!purgarVencidas || scNow == null) return 0;
        int n = 0;
        n += purgarLista(sinRuta, scNow);
        n += purgarLista(replanificables, scNow);
        sinRutaDefinitivo += n;
        return n;
    }

    private int purgarLista(Deque<LoteEnvio> deque, LocalDateTime scNow) {
        int count = 0;
        Iterator<LoteEnvio> it = deque.iterator();
        while (it.hasNext()) {
            LoteEnvio b = it.next();
            LocalDateTime deadline = b.getReadyTime().plusHours(b.getSlaLimitHours());
            if (deadline.isBefore(scNow)) {
                it.remove();
                if (descartarDefinitivamente(b)) count++;
            }
        }
        return count;
    }

    private void aplicarTope() {
        if (maxSize <= 0) return;
        while (sinRuta.size() + replanificables.size() > maxSize) {
            LoteEnvio b;
            if (!sinRuta.isEmpty())              b = sinRuta.pollFirst();
            else if (!replanificables.isEmpty()) b = replanificables.pollFirst();
            else break;
            if (descartarDefinitivamente(b)) sinRutaDefinitivo++;
        }
    }

    private boolean descartarDefinitivamente(LoteEnvio b) {
        if (onDescarte != null) onDescarte.accept(b);
        return b.getAssignedRoute() == null || b.getAssignedRoute().isEmpty();
    }

    private void actualizarPico() {
        int total = sinRuta.size() + replanificables.size();
        if (total > picoHistorico) picoHistorico = total;
    }

    public int     size()              { return sinRuta.size() + replanificables.size(); }
    public int     sinRutaCount()      { return sinRuta.size(); }
    public int     replanificablesCount() { return replanificables.size(); }
    public int     sinRutaDefinitivo() { return sinRutaDefinitivo; }
    public int     picoHistorico()     { return picoHistorico; }
}
