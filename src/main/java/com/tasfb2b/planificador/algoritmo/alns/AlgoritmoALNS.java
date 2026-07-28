package com.tasfb2b.planificador.algoritmo.alns;


import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
public class AlgoritmoALNS {

    public double factorDestruccion = 0.20;
    public double tempInicial       = 500.0;
    public double tasaEnfriamiento  = 0.85;
    public double tempMinima        = 0.1;
    public int    tamañoMinBloque   = 3;
    public int    longitudSegmento  = 3;

    public long   tiempoLimiteMs = Long.MAX_VALUE;

    private static final double RECOMPENSA_NUEVO_MEJOR = 3.0;
    private static final double RECOMPENSA_MEJORA      = 2.0;
    private static final double RECOMPENSA_ACEPTADO    = 1.0;
    private static final double FACTOR_REACCION        = 0.15;

    private final Grafo                  grafo;
    private final OperadorReparacionVoraz   enrutador;
    private final List<OperadorDestruccion>  operadoresDestruccion;

    private final double[] pesos;
    private final double[] puntajes;
    private final int[]    usos;

    private SolucionAlns     solucionActual;
    private SolucionAlns     mejorSolucion;
    private Map<Long, Integer> vueloActual;
    private Map<Long, Integer> aeropuertoActual;
    private Map<Long, Integer> mejorVuelo;
    private Map<Long, Integer> mejorAeropuerto;

    private double temperatura;

    private Random rng = new Random();

    public void setAleatorio(Random rng) {
        if (rng == null) return;
        this.rng = rng;
        if (operadoresDestruccion != null) {
            for (OperadorDestruccion op : operadoresDestruccion) op.setAleatorio(rng);
        }
    }

    public AlgoritmoALNS(Grafo grafo,
                          OperadorReparacionVoraz enrutador,
                          List<LoteEnvio> batches,
                          Map<Long, Integer> blockFlight,
                          Map<Long, Integer> blockAirport) {
        this(grafo, enrutador, batches, blockFlight, blockAirport, null);
    }

    public AlgoritmoALNS(Grafo grafo,
                          OperadorReparacionVoraz enrutador,
                          List<LoteEnvio> batches,
                          Map<Long, Integer> blockFlight,
                          Map<Long, Integer> blockAirport,
                          PlanificadorProperties props) {
        if (props != null) {
            PlanificadorProperties.Alns a = props.getAlns();
            this.factorDestruccion = a.getDestroyFactor();
            this.tempInicial       = a.getInitialTemp();
            this.tasaEnfriamiento  = a.getCoolingRate();
            this.tempMinima        = a.getMinTemp();
            this.tamañoMinBloque   = a.getMinBlockSize();
            this.longitudSegmento  = a.getSegmentLength();
        }

        this.grafo       = grafo;
        this.enrutador   = enrutador;
        this.temperatura = tempInicial;

        if (props != null && props.getAlns().getOperadoresDestroy() != null
                && !props.getAlns().getOperadoresDestroy().isEmpty()) {
            this.operadoresDestruccion = construirOperadoresDestruccion(props.getAlns().getOperadoresDestroy());
        } else {
            this.operadoresDestruccion = List.of(
                    new OperadorDestruccionCapacidad(),
                    new OperadorDestruccionPeorRuta()
            );
        }

        int n = operadoresDestruccion.size();
        this.pesos    = new double[n];
        this.puntajes = new double[n];
        this.usos     = new int[n];
        Arrays.fill(pesos, 1.0);

        if (props != null) {
            this.solucionActual = new SolucionAlns(batches,
                    props.getObjetivo().getPesoTransit(),
                    props.getObjetivo().getPesoTarde(),
                    props.getObjetivo().getPesoUsoAlmacen());
        } else {
            this.solucionActual = new SolucionAlns(batches);
        }
        this.vueloActual      = new HashMap<>(blockFlight);
        this.aeropuertoActual = new HashMap<>(blockAirport);

        this.mejorSolucion  = solucionActual.clonar();
        this.mejorVuelo     = new HashMap<>(vueloActual);
        this.mejorAeropuerto = new HashMap<>(aeropuertoActual);
    }

    public void ejecutar(int maxIterations) {
        if (solucionActual.getLotes().size() < tamañoMinBloque) return;

        double bestCost    = mejorSolucion.calcularCosto();
        double currentCost = bestCost;
        long   tInicio     = System.nanoTime();

        for (int iter = 0; iter < maxIterations && temperatura > tempMinima; iter++) {

            if (tiempoLimiteMs < Long.MAX_VALUE) {
                long elapsedMs = (System.nanoTime() - tInicio) / 1_000_000;
                if (elapsedMs >= tiempoLimiteMs) {
                    log.warn("ALNS abortado por presupuesto de tiempo: iter {}/{} ({}ms >= {}ms)",
                            iter, maxIterations, elapsedMs, tiempoLimiteMs);
                    break;
                }
            }

            int selectedIdx = seleccionarOperadorDestruccion();
            usos[selectedIdx]++;

            SolucionAlns     candidate = solucionActual.clonar();
            Map<Long, Integer> cFlight  = new HashMap<>(vueloActual);
            Map<Long, Integer> cAirport = new HashMap<>(aeropuertoActual);

            List<LoteEnvio> unassigned =
                    operadoresDestruccion.get(selectedIdx).destruir(candidate, factorDestruccion);

            for (LoteEnvio b : unassigned) {
                enrutador.liberarDeBloque(b, cFlight, cAirport);
                b.limpiarRuta();
            }

            enrutador.reparar(candidate, unassigned, cFlight, cAirport);

            double candidateCost = candidate.calcularCosto();
            boolean accepted     = aceptar(currentCost, candidateCost, temperatura);

            double reward = 0;
            if (accepted) {
                boolean mejora = candidateCost < currentCost;

                solucionActual  = candidate;
                vueloActual     = cFlight;
                aeropuertoActual = cAirport;
                currentCost     = candidateCost;

                if (candidateCost < bestCost) {
                    reward        = RECOMPENSA_NUEVO_MEJOR;
                    mejorSolucion = solucionActual.clonar();
                    mejorVuelo    = new HashMap<>(vueloActual);
                    mejorAeropuerto = new HashMap<>(aeropuertoActual);
                    bestCost      = candidateCost;
                } else if (mejora) {
                    reward = RECOMPENSA_MEJORA;
                } else {
                    reward = RECOMPENSA_ACEPTADO;
                }
            }
            puntajes[selectedIdx] += reward;

            if ((iter + 1) % longitudSegmento == 0) actualizarPesos();

            temperatura *= tasaEnfriamiento;
        }
    }

    private int seleccionarOperadorDestruccion() {
        double total = 0;
        for (double w : pesos) total += w;
        double rand = rng.nextDouble() * total;
        double cum  = 0;
        for (int i = 0; i < pesos.length; i++) {
            cum += pesos[i];
            if (rand <= cum) return i;
        }
        return pesos.length - 1;
    }

    private void actualizarPesos() {
        for (int i = 0; i < pesos.length; i++) {
            if (usos[i] > 0)
                pesos[i] = (1 - FACTOR_REACCION) * pesos[i]
                           + FACTOR_REACCION * (puntajes[i] / usos[i]);
            puntajes[i] = 0;
            usos[i]   = 0;
        }
    }

    private boolean aceptar(double current, double candidate, double temp) {
        if (candidate <= current) return true;
        return rng.nextDouble() < Math.exp((current - candidate) / temp);
    }

    public SolucionAlns      getMejorSolucion()      { return mejorSolucion; }
    public Map<Long, Integer> getMejorBloqueVuelo()   { return mejorVuelo;   }
    public Map<Long, Integer> getMejorBloqueAeropuerto() { return mejorAeropuerto;  }

    private static List<OperadorDestruccion> construirOperadoresDestruccion(List<String> nombres) {
        java.util.ArrayList<OperadorDestruccion> ops = new java.util.ArrayList<>(nombres.size());
        for (String n : nombres) {
            switch (n.toLowerCase().trim()) {
                case "capacity"           -> ops.add(new OperadorDestruccionCapacidad());
                case "worst-route"        -> ops.add(new OperadorDestruccionPeorRuta());
                case "random"             -> ops.add(new OperadorDestruccionAleatoria());
                case "airport-congestion" -> ops.add(new OperadorDestruccionCongestionAeropuerto());
                default -> log.warn("Operador destruir desconocido en config: '{}' (ignorado)", n);
            }
        }
        if (ops.isEmpty()) {
            log.warn("Lista de operadores destruir vacía tras parseo — usando defaults");
            ops.add(new OperadorDestruccionCapacidad());
            ops.add(new OperadorDestruccionPeorRuta());
        }
        return ops;
    }
}
