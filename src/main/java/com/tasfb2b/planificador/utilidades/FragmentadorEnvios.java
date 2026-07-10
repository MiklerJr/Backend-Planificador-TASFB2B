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

/**
 * Fragmentación estática de envíos en sub-lotes (caso E1 del profesor: un envío con cantidad mayor
 * que la capacidad máxima de los aviones jamás encontraría ruta y rebotaría en el backlog hasta
 * vencer su SLA). Un envío se parte en N {@link LoteEnvio} independientes UNA sola vez, al nacer
 * (MapeadorAlgoritmo o inyección en vivo); el motor (ALNS/ACO/backlog) sólo ve lotes independientes,
 * así que no se toca. El reparto es puro y determinista (imprescindible: procesarBloque re-mapea la
 * demanda dos veces por bloque y ambas pasadas deben producir ids/cantidades idénticos).
 *
 * <p>Identidad de un sub-lote: {@code <idPadre>-F<n>} (n = 1..N). No ambiguo: los ids del dataset
 * terminan en dígitos ({@code ICAO-000000001}) y los sintéticos son {@code INV-<bloque>-<n>}, ninguno
 * contiene el marcador {@code -F}.
 */
public final class FragmentadorEnvios {

    private static final Logger log = LoggerFactory.getLogger(FragmentadorEnvios.class);

    /** Marcador del sufijo de sub-lote: {@code <idPadre>-F<n>}. */
    public static final String SUFIJO = "-F";

    private static final Pattern PATRON_SUBLOTE = Pattern.compile("^(.+)-F([0-9]+)$");

    private FragmentadorEnvios() {}

    /**
     * Umbral efectivo de maletas por sub-lote para el grafo de la corrida. Si la fragmentación está
     * deshabilitada devuelve {@link Integer#MAX_VALUE} (nadie se fragmenta). Con un valor explícito lo
     * respeta; con 0 (auto) usa la capacidad máxima de avión del grafo del run — que ya lleva los
     * overrides de capacidad EN CALIENTE y las altas append-only, así que las altas en caliente quedan
     * cubiertas gratis.
     */
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
        return max > 0 ? max : Integer.MAX_VALUE;   // sin capacidad conocida ⇒ no fragmentar
    }

    /**
     * Fragmenta un lote en sub-lotes si su cantidad supera {@code umbral}. Si {@code cantidad <= umbral}
     * devuelve {@code List.of(lote)} SIN tocarlo (no-op para los ~9,5 M de envíos del dataset con
     * cantidades 001-003). El reparto es exacto: {@code n = ceil(cantidad/umbral)}, {@code base =
     * cantidad/n}, y los primeros {@code cantidad%n} sub-lotes llevan {@code base+1} (p. ej. 1001/500 →
     * 334+334+333). El tope {@code maxSublotes} acota el número de sub-lotes (anti-abuso ante una
     * inyección con cantidad absurda): si {@code n} lo excede se emiten {@code maxSublotes} sub-lotes
     * (algunos por encima del umbral, que seguirán sin ruta como hoy) con un WARN.
     */
    public static List<LoteEnvio> fragmentar(LoteEnvio lote, int umbral, int maxSublotes) {
        if (lote == null) return List.of();
        int cantidad = lote.getCantidad();
        if (umbral <= 0 || cantidad <= umbral) return List.of(lote);   // no fragmenta

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

    /** true si {@code id} tiene la forma de un sub-lote fragmentado ({@code <idPadre>-F<n>}). */
    public static boolean esIdSubLote(String id) {
        return id != null && PATRON_SUBLOTE.matcher(id).matches();
    }

    /** id del padre de {@code id}: la parte antes de {@code -F<n>} si es sub-lote, o {@code id} tal cual. */
    public static String idPadreDe(String id) {
        if (id == null) return null;
        Matcher m = PATRON_SUBLOTE.matcher(id);
        return m.matches() ? m.group(1) : id;
    }

    /** número de fragmento (1..N) de {@code id} si es sub-lote; 0 si no lo es. */
    public static int numeroFragmentoDe(String id) {
        if (id == null) return 0;
        Matcher m = PATRON_SUBLOTE.matcher(id);
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }
}
