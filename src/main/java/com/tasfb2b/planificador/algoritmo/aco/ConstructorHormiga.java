package com.tasfb2b.planificador.algoritmo.aco;

import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz;
import com.tasfb2b.planificador.algoritmo.alns.OperadorReparacionVoraz.RutaCandidata;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Una hormiga del ACO: construye UNA solución del bloque recorriendo la frontera de envíos
 * pendientes, eligiendo en cada paso (vía {@link RuletaSeleccion}) qué envío atender y con qué
 * ruta, y aplicando la ocupación sobre una copia simulada del bloque. Se reutiliza entre hormigas.
 */
final class ConstructorHormiga {

    private static final int MAX_ROUTE_CANDIDATES = 3;
    private static final int DECISION_FRONTIER = 16;
    private static final int REGRET_FRONTIER = 4;

    private final OperadorReparacionVoraz enrutador;
    private final GeneradorRutas generador;
    private final Heuristica heuristica;
    private final RuletaSeleccion ruleta;
    private final RastroFeromonas feromonas;
    private final Random random;

    ConstructorHormiga(OperadorReparacionVoraz enrutador,
                       GeneradorRutas generador,
                       Heuristica heuristica,
                       RuletaSeleccion ruleta,
                       RastroFeromonas feromonas,
                       Random random) {
        this.enrutador = enrutador;
        this.generador = generador;
        this.heuristica = heuristica;
        this.ruleta = ruleta;
        this.feromonas = feromonas;
        this.random = random;
    }

    SolucionBloque construir(List<LoteEnvio> base,
                             Map<Long, Integer> blockFlight,
                             Map<Long, Integer> blockAirport,
                             Map<LoteEnvio, String> batchKeys,
                             long deadline) {
        Map<Long, Integer> simFlight = new HashMap<>(blockFlight);
        Map<Long, Integer> simAirport = new HashMap<>(blockAirport);
        BolsaPendientes pendientes = new BolsaPendientes(base);
        List<Asignacion> asignaciones = new ArrayList<>();

        Map<LoteEnvio, OpcionEnvio> evalCache = new IdentityHashMap<>(base.size() * 2);

        while (!pendientes.isEmpty() && System.nanoTime() < deadline) {
            List<OpcionEnvio> opciones = evaluarFrontier(
                    pendientes, simFlight, simAirport, batchKeys, evalCache);
            if (opciones.isEmpty()) {
                RefEnvio ref = pendientes.first();
                if (ref == null) break;
                pendientes.remove(ref);
                continue;
            }

            OpcionEnvio opcion = ruleta.elegirBatch(opciones);
            if (opcion == null) break;

            Decision elegida = ruleta.elegirRuta(opcion);
            if (elegida == null) {
                pendientes.remove(opcion.ref);
                evalCache.remove(opcion.ref.batch);
                continue;
            }

            enrutador.aplicarCandidatoBloque(elegida.batch, elegida.route, simFlight, simAirport);
            asignaciones.add(new Asignacion(elegida.batch, elegida.route, elegida.key, elegida.batchKey));
            pendientes.remove(opcion.ref);

            evalCache.remove(elegida.batch);
            Set<Long> tocadas = enrutador.clavesOcupadas(elegida.route, elegida.batch);
            if (!tocadas.isEmpty() && !evalCache.isEmpty()) {
                evalCache.values().removeIf(opt -> !Collections.disjoint(opt.occupiedKeys, tocadas));
            }
        }

        return new SolucionBloque(asignaciones, base.size());
    }

    private List<OpcionEnvio> evaluarFrontier(BolsaPendientes pendientes,
                                              Map<Long, Integer> simFlight,
                                              Map<Long, Integer> simAirport,
                                              Map<LoteEnvio, String> batchKeys,
                                              Map<LoteEnvio, OpcionEnvio> evalCache) {
        List<OpcionEnvio> opciones = new ArrayList<>();
        List<RefEnvio> sinRuta = new ArrayList<>();
        for (RefEnvio ref : pendientes.frontier(REGRET_FRONTIER, random)) {
            OpcionEnvio cached = evalCache.get(ref.batch);
            if (cached != null) {
                opciones.add(cached);
                continue;
            }
            List<RutaCandidata> rutas = generador.obtenerRutas(
                    ref.batch, simFlight, simAirport, MAX_ROUTE_CANDIDATES);
            if (rutas.isEmpty()) {
                sinRuta.add(ref);
                continue;
            }
            int alternativasOnTime = 0;
            for (RutaCandidata r : rutas) {
                if (r.isCumpleSLA()) alternativasOnTime++;
            }
            double regret = heuristica.regret(ref.batch, rutas, alternativasOnTime);
            double heuristic = heuristica.heuristicaBatch(ref.batch)
                    * (1.0 + heuristica.heuristica(ref.batch, rutas.get(0), alternativasOnTime))
                    * (1.0 + Math.min(2.0, regret));
            String bKey = batchKeys.get(ref.batch);
            double weighted = feromonas.pesoBatch(bKey, heuristic);
            Set<Long> occupiedKeys = generador.clavesDeRutas(ref.batch, rutas);
            OpcionEnvio opt = new OpcionEnvio(ref, rutas, alternativasOnTime, regret,
                    heuristic, weighted, bKey, occupiedKeys);
            evalCache.put(ref.batch, opt);
            opciones.add(opt);
        }
        for (RefEnvio ref : sinRuta) {
            pendientes.remove(ref);
        }
        return opciones;
    }

    private static final class BolsaPendientes {
        private final List<LoteEnvio> items;

        BolsaPendientes(List<LoteEnvio> source) {
            this.items = new ArrayList<>(source);
        }

        boolean isEmpty() {
            return items.isEmpty();
        }

        RefEnvio first() {
            return items.isEmpty() ? null : new RefEnvio(items.get(0), 0);
        }

        List<RefEnvio> frontier(int limit, Random random) {
            if (items.isEmpty()) return List.of();
            int n = Math.min(Math.max(1, limit), items.size());
            List<RefEnvio> refs = new ArrayList<>(n + 1);
            for (int i = 0; i < n; i++) {
                refs.add(new RefEnvio(items.get(i), i));
            }
            if (items.size() > DECISION_FRONTIER) {
                int idx = DECISION_FRONTIER + random.nextInt(items.size() - DECISION_FRONTIER);
                refs.add(new RefEnvio(items.get(idx), idx));
            } else if (items.size() > n) {
                int idx = n + random.nextInt(items.size() - n);
                refs.add(new RefEnvio(items.get(idx), idx));
            }
            return refs;
        }

        void remove(RefEnvio ref) {
            if (ref == null || items.isEmpty()) return;
            if (ref.index >= 0 && ref.index < items.size() && items.get(ref.index) == ref.batch) {
                removeAt(ref.index);
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) == ref.batch) {
                    removeAt(i);
                    return;
                }
            }
        }

        private void removeAt(int index) {
            int last = items.size() - 1;
            if (index != last) {
                items.set(index, items.get(last));
            }
            items.remove(last);
        }
    }
}
