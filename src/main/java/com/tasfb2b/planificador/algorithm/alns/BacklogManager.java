package com.tasfb2b.planificador.algorithm.alns;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Backlog acumulativo de pedidos pendientes de planificación o replanificación
 * entre bloques (modelo Sc del cliente).
 *
 * <p>Mantiene tres categorías:
 * <ul>
 *   <li><b>sinRuta</b>: batches que no encontraron ruta en el bloque previo
 *       y deben reintentarse cuando avance el horizonte.</li>
 *   <li><b>replanificables</b>: batches enrutados pero con poca holgura SLA
 *       (próximos a tardar) que podrían beneficiarse de una nueva ruta.</li>
 *   <li><b>sinRutaDefinitivo</b> (contador): batches descartados sin ruta
 *       utilizable — SLA ya vencido o tope absoluto excedido. No se reintentan.</li>
 * </ul>
 *
 * <p>Single-thread por construcción: cada simulación crea su propia instancia.
 */
@Slf4j
public class BacklogManager {

    private final Deque<LuggageBatch> sinRuta         = new ArrayDeque<>();
    private final Deque<LuggageBatch> replanificables = new ArrayDeque<>();
    private int     sinRutaDefinitivo = 0;
    private int     picoHistorico     = 0;
    private final int     maxSize;
    private final boolean purgarVencidas;
    private final Consumer<LuggageBatch> onDescarte;

    public BacklogManager(int maxSize, boolean purgarVencidas) {
        this(maxSize, purgarVencidas, null);
    }

    /**
     * @param onDescarte hook invocado por cada batch que sale DEFINITIVAMENTE del backlog
     *                   (purga por SLA vencido o tope absoluto). El llamador lo usa para
     *                   liberar la ocupación global de las rutas rotas por cancelación
     *                   ({@code releaseFromGlobal} + {@code clearRoute}) — este manager no
     *                   conoce al enrutador. Puede ser {@code null}.
     */
    public BacklogManager(int maxSize, boolean purgarVencidas, Consumer<LuggageBatch> onDescarte) {
        this.maxSize        = Math.max(0, maxSize);
        this.purgarVencidas = purgarVencidas;
        this.onDescarte     = onDescarte;
    }

    /** Marca un batch como sin ruta para reintentar en el próximo bloque. */
    public void addSinRuta(LuggageBatch batch) {
        sinRuta.addLast(batch);
        actualizarPico();
        aplicarTope();
    }

    /**
     * Marca un batch enrutado pero con poca holgura SLA para intentar mejorar
     * su ruta en el próximo bloque (replanificación preventiva).
     */
    public void addReplanificable(LuggageBatch batch) {
        replanificables.addLast(batch);
        actualizarPico();
        aplicarTope();
    }

    /**
     * Devuelve y vacía las listas de batches pendientes (sinRuta + replanificables).
     * El llamador es responsable de liberar capacidad global de los replanificables
     * antes de reasignar.
     */
    public List<LuggageBatch> pollPendientes() {
        if (sinRuta.isEmpty() && replanificables.isEmpty()) return List.of();
        List<LuggageBatch> result = new ArrayList<>(sinRuta.size() + replanificables.size());
        result.addAll(sinRuta);
        result.addAll(replanificables);
        sinRuta.clear();
        replanificables.clear();
        return result;
    }

    /**
     * Vista de solo lectura de los pendientes actuales (sinRuta + replanificables) SIN vaciarlos.
     * Útil para contabilizar su ocupación de almacén de origen mientras esperan.
     */
    public List<LuggageBatch> peekPendientes() {
        if (sinRuta.isEmpty() && replanificables.isEmpty()) return List.of();
        List<LuggageBatch> result = new ArrayList<>(sinRuta.size() + replanificables.size());
        result.addAll(sinRuta);
        result.addAll(replanificables);
        return result;
    }

    /**
     * Limita la cantidad de batches devueltos en {@link #pollPendientes()} aplicando
     * un máximo: los excedentes quedan en el backlog para el siguiente bloque.
     * Útil para acotar el costo del ALNS por bloque.
     */
    public List<LuggageBatch> pollPendientes(int max) {
        if (max <= 0) return List.of();
        List<LuggageBatch> result = new ArrayList<>(Math.min(max, size()));
        // Prioridad: sinRuta primero (críticos), luego replanificables.
        while (!sinRuta.isEmpty() && result.size() < max) {
            result.add(sinRuta.pollFirst());
        }
        while (!replanificables.isEmpty() && result.size() < max) {
            result.add(replanificables.pollFirst());
        }
        return result;
    }

    /**
     * Anti-thrash: devuelve hasta {@code max} pendientes priorizando los de
     * DEADLINE más cercano ({@code readyTime + SLA}), mezclando sinRuta y replanificables.
     * Los no devueltos (los MENOS urgentes) permanecen en el backlog para el siguiente
     * bloque — no se pierde ninguno; {@code purgarVencidas} los purgará si vencen. Acota el
     * reproceso por bloque para que el backlog no le robe {@code Ta} a la demanda nueva.
     * {@code max<=0} => devuelve todos (equivale a {@link #pollPendientes()}).
     */
    public List<LuggageBatch> pollPendientesUrgentes(int max) {
        int total = sinRuta.size() + replanificables.size();
        if (max <= 0 || max >= total) {
            return pollPendientes();
        }
        List<LuggageBatch> todos = new ArrayList<>(total);
        todos.addAll(sinRuta);
        todos.addAll(replanificables);
        sinRuta.clear();
        replanificables.clear();
        todos.sort(Comparator.comparing(
                b -> b.getReadyTime().plusHours(b.getSlaLimitHours())));
        List<LuggageBatch> out = new ArrayList<>(todos.subList(0, max));
        // Re-encolar los menos urgentes (ya ordenados por deadline) para el próximo bloque.
        for (int i = max; i < todos.size(); i++) {
            sinRuta.addLast(todos.get(i));
        }
        return out;
    }

    /**
     * Descarta del backlog cualquier batch cuyo SLA ya venció ({@code readyTime + slaLimit < scNow}).
     * Cada descartado pasa antes por el hook {@code onDescarte}, que libera la ocupación global
     * de las rutas rotas por cancelación y las limpia. Solo cuentan como {@code sinRutaDefinitivo}
     * los que salen SIN ruta utilizable: un replanificable con ruta válida on-time ya tiene su
     * entrega comprometida dentro del SLA — sale del backlog sin contarse como incumplimiento
     * (sigue figurando como enrutado en métricas/auditoría y no dispara el colapso del E3).
     * Solo activo si el manager fue creado con {@code purgarVencidas=true}.
     *
     * @return cantidad movida a {@code sinRutaDefinitivo} en esta llamada (vencidos reales)
     */
    public int purgarVencidas(LocalDateTime scNow) {
        if (!purgarVencidas || scNow == null) return 0;
        int n = 0;
        n += purgarLista(sinRuta, scNow);
        n += purgarLista(replanificables, scNow);
        sinRutaDefinitivo += n;
        return n;
    }

    private int purgarLista(Deque<LuggageBatch> deque, LocalDateTime scNow) {
        int count = 0;
        Iterator<LuggageBatch> it = deque.iterator();
        while (it.hasNext()) {
            LuggageBatch b = it.next();
            LocalDateTime deadline = b.getReadyTime().plusHours(b.getSlaLimitHours());
            if (deadline.isBefore(scNow)) {
                it.remove();
                if (descartarDefinitivamente(b)) count++;
            }
        }
        return count;
    }

    /** Aplica el tope absoluto: si se excede, los más viejos salen del backlog definitivamente. */
    private void aplicarTope() {
        if (maxSize <= 0) return;
        while (sinRuta.size() + replanificables.size() > maxSize) {
            LuggageBatch b;
            if (!sinRuta.isEmpty())              b = sinRuta.pollFirst();
            else if (!replanificables.isEmpty()) b = replanificables.pollFirst();
            else break;
            if (descartarDefinitivamente(b)) sinRutaDefinitivo++;
        }
    }

    /**
     * Pasa el batch por el hook {@code onDescarte} (liberación de rutas rotas) y decide si
     * cuenta como definitivo: solo si sale SIN ruta utilizable tras el hook.
     */
    private boolean descartarDefinitivamente(LuggageBatch b) {
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
