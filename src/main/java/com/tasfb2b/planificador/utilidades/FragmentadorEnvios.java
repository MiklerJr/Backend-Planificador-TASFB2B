package com.tasfb2b.planificador.utilidades;

import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FragmentadorEnvios {

    private static final Logger log = LoggerFactory.getLogger(FragmentadorEnvios.class);

    public static final String SUFIJO = "-F";

    private static final Pattern PATRON_SUBLOTE = Pattern.compile("^(.+)-F([0-9]+)$");

    private FragmentadorEnvios() {}

    public static int umbralEfectivo(PlanificadorProperties.Fragmentacion cfg, Grafo grafo) {
        if (cfg == null || !cfg.isHabilitada()) return Integer.MAX_VALUE;
        int explicito = cfg.getMaxMaletasPorSublote();
        if (explicito > 0) return explicito;
        int max = 0;
        if (grafo != null) {
            for (Arista a : grafo.aristas) {
                if (a.capacidad > max) max = a.capacidad;
            }
        }
        return max > 0 ? max : Integer.MAX_VALUE;
    }

    public static List<LoteEnvio> fragmentar(LoteEnvio lote, int umbral, int maxSublotes) {
        if (lote == null) return List.of();
        int cantidad = lote.getCantidad();
        if (umbral <= 0 || cantidad <= umbral) return List.of(lote);

        int n = (int) Math.ceil((double) cantidad / umbral);
        if (maxSublotes > 0 && n > maxSublotes) {
            log.warn("Envío {} (cantidad {}) requeriría {} sub-lotes con umbral {}; se limita a {} "
                    + "(algunos sub-lotes > umbral seguirán sin ruta)",
                    lote.getId(), cantidad, n, umbral, maxSublotes);
            n = maxSublotes;
        }

        int base = cantidad / n;
        int resto = cantidad % n;
        List<LoteEnvio> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            int cant = base + (i <= resto ? 1 : 0);
            LoteEnvio sub = new LoteEnvio(
                    lote.getId() + SUFIJO + i, cant, lote.getHorasLimiteSla(),
                    lote.getCodigoOrigen(), lote.getCodigoDestino(), lote.getTiempoListo());
            sub.setClienteId(lote.getClienteId());
            sub.setSintetico(lote.isSintetico());
            sub.setIdPadre(lote.getId());
            sub.setFragmento(i);
            sub.setTotalFragmentos(n);
            out.add(sub);
        }
        return out;
    }

    public static boolean esIdSubLote(String id) {
        return id != null && PATRON_SUBLOTE.matcher(id).matches();
    }

    public static String idPadreDe(String id) {
        if (id == null) return null;
        Matcher m = PATRON_SUBLOTE.matcher(id);
        return m.matches() ? m.group(1) : id;
    }

    public static int numeroFragmentoDe(String id) {
        if (id == null) return 0;
        Matcher m = PATRON_SUBLOTE.matcher(id);
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }
}
