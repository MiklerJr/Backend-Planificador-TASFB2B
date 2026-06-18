package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.Edge;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.dto.MuestraEnvio;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Construye una muestra resumida de hasta N envíos para inspección rápida.
 * Pensado para escenario 2 con motor ALNS — el cliente pidió ver una vista
 * legible de algunos envíos al final de la corrida.
 */
@Slf4j
@Service
public class MuestraService {

    /** Tamaño por defecto de la muestra (cumple "máximo 25" pedido por el cliente). */
    public static final int LIMITE_DEFAULT = 25;

    /**
     * Selecciona los primeros {@code limit} batches y los convierte a {@link MuestraEnvio}.
     * Mezcla representativa: prioriza enrutados, luego sin ruta (si quedan huecos).
     */
    public List<MuestraEnvio> construir(Collection<LuggageBatch> batches, int limit) {
        if (batches == null || batches.isEmpty()) return List.of();
        if (limit <= 0) limit = LIMITE_DEFAULT;

        List<MuestraEnvio> out = new ArrayList<>(limit);
        // Primero enrutados (más informativos), luego sin ruta para llenar el cupo.
        for (LuggageBatch b : batches) {
            if (out.size() >= limit) break;
            boolean enrutado = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (enrutado) out.add(toMuestra(b));
        }
        for (LuggageBatch b : batches) {
            if (out.size() >= limit) break;
            boolean enrutado = b.getAssignedRoute() != null && !b.getAssignedRoute().isEmpty();
            if (!enrutado) out.add(toMuestra(b));
        }
        return out;
    }

    private MuestraEnvio toMuestra(LuggageBatch b) {
        MuestraEnvio m = new MuestraEnvio();
        m.setIdMaleta(b.getId());
        m.setIdCliente(b.getClienteId());
        m.setOrigen(b.getOriginCode());
        m.setDestino(b.getDestCode());
        m.setCantidad(b.getQuantity());
        m.setSlaLimiteMin(b.getSlaLimitHours() * 60);

        List<Edge> ruta = b.getAssignedRoute();
        boolean enrutado = ruta != null && !ruta.isEmpty();
        if (!enrutado) {
            m.setRuta("sin ruta");
            m.setTiempoTotalMin(0);
            m.setCumpleSLA(false);
            m.setTardado(false);
            return m;
        }

        StringBuilder sb = new StringBuilder(ruta.get(0).from.code);
        for (Edge e : ruta) sb.append("->").append(e.to.code);
        m.setRuta(sb.toString());
        m.setTiempoTotalMin((int) Math.round(b.getTotalTransitTimeMins()));
        m.setCumpleSLA(b.isCumpleSLA());
        m.setTardado(!b.isCumpleSLA());
        return m;
    }

    /** Serializa una muestra a CSV (8 columnas + cabecera). */
    public String aCsv(List<MuestraEnvio> filas) {
        StringBuilder sb = new StringBuilder();
        sb.append("idMaleta,idCliente,origen,destino,cantidad,ruta,tiempoTotalMin,slaLimiteMin,cumpleSLA,tardado\n");
        for (MuestraEnvio m : filas) {
            sb.append(csv(m.getIdMaleta())).append(',')
              .append(m.getIdCliente() != null ? m.getIdCliente() : "").append(',')
              .append(csv(m.getOrigen())).append(',')
              .append(csv(m.getDestino())).append(',')
              .append(m.getCantidad()).append(',')
              .append(csv(m.getRuta())).append(',')
              .append(m.getTiempoTotalMin()).append(',')
              .append(m.getSlaLimiteMin()).append(',')
              .append(m.isCumpleSLA()).append(',')
              .append(m.isTardado())
              .append('\n');
        }
        return sb.toString();
    }

    /** Imprime la muestra al log con formato legible (tabla). */
    public void imprimir(List<MuestraEnvio> filas, String contexto) {
        if (filas == null || filas.isEmpty()) {
            log.info("Muestra ({}): vacía", contexto);
            return;
        }
        log.info("─── Muestra de envíos ({}) — {} filas ────────────────────────────────────", contexto, filas.size());
        log.info(String.format("%-12s %-9s %-6s %-6s %-5s %-6s %-7s %-7s %-7s  %s",
                "idMaleta", "idCliente", "origen", "dest", "cant", "tiempo", "sla", "cumple", "tardado", "ruta"));
        for (MuestraEnvio m : filas) {
            log.info(String.format("%-12s %-9s %-6s %-6s %-5d %-6d %-7d %-7s %-7s  %s",
                    m.getIdMaleta(),
                    m.getIdCliente() == null ? "—" : m.getIdCliente().toString(),
                    m.getOrigen(), m.getDestino(),
                    m.getCantidad(), m.getTiempoTotalMin(), m.getSlaLimiteMin(),
                    m.isCumpleSLA(), m.isTardado(),
                    m.getRuta()));
        }
        log.info("───────────────────────────────────────────────────────────────────────────");
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
