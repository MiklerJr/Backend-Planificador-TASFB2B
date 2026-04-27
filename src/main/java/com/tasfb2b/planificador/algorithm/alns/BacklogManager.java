package com.tasfb2b.planificador.algorithm.alns;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

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
 *   <li><b>sinRutaDefinitivo</b> (contador): batches descartados — SLA ya
 *       vencido o tope absoluto excedido. No se reintentan.</li>
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

    public BacklogManager(int maxSize, boolean purgarVencidas) {
        this.maxSize        = Math.max(0, maxSize);
        this.purgarVencidas = purgarVencidas;
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

    /** Devuelve {@code replanificables} sin vaciar (útil para evaluar) — uso de lectura. */
    public List<LuggageBatch> snapshotReplanificables() {
        return new ArrayList<>(replanificables);
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
     * Descarta del backlog cualquier batch cuyo SLA ya venció ({@code readyTime + slaLimit < scNow}).
     * Estos batches no tienen forma de cumplir el contrato aunque se enruten — se mueven
     * a {@code sinRutaDefinitivo}. Solo activo si el manager fue creado con {@code purgarVencidas=true}.
     *
     * @return cantidad purgada en esta llamada
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
                count++;
            }
        }
        return count;
    }

    /** Aplica el tope absoluto: si se excede, los más viejos pasan a sinRutaDefinitivo. */
    private void aplicarTope() {
        if (maxSize <= 0) return;
        while (sinRuta.size() + replanificables.size() > maxSize) {
            if (!sinRuta.isEmpty())          sinRuta.pollFirst();
            else if (!replanificables.isEmpty()) replanificables.pollFirst();
            else break;
            sinRutaDefinitivo++;
        }
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
