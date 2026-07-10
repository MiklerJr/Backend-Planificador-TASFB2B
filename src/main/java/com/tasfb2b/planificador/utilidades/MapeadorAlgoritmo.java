package com.tasfb2b.planificador.utilidades;

import com.tasfb2b.planificador.algoritmo.grafo.Arista;
import java.time.Duration;
import java.time.LocalDateTime;
import com.tasfb2b.planificador.algoritmo.grafo.Grafo;
import com.tasfb2b.planificador.algoritmo.grafo.Nodo;
import com.tasfb2b.planificador.algoritmo.alns.LoteEnvio;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Envio;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MapeadorAlgoritmo {


    public Grafo mapearAGrafo(List<Aeropuerto> aeropuertos, List<Vuelo> vuelos) {
        Grafo graph = new Grafo();

        // 1. Mapear Nodos (Aeropuertos)
        for (Aeropuerto a : aeropuertos) {
            graph.agregarNodo(a.getCodigo());
            Nodo nodo = graph.nodos.get(a.getCodigo());
            int capacidadAlmacen = a.getCapacidad() != null ? a.getCapacidad() : 0;
            nodo.capacidad = capacidadAlmacen;
            nodo.capacidadAlmacen = capacidadAlmacen;
        }

        // 2. Mapear Aristas (Vuelos)
        int edgeIdx = 0;
        for (Vuelo v : vuelos) {
            graph.agregarArista(construirArista(v, graph, edgeIdx++));
        }

        return graph;
    }

    /**
     * Construye la arista de un vuelo con la normalización UTC canónica (depUtc = salida − offset del
     * origen; duración real con módulo 24 h). Única fuente del mapeo vuelo→arista: la usa el bucle de
     * {@link #mapearAGrafo} y las altas EN CALIENTE (AltasEnCalienteService), que deben producir una
     * arista idéntica a la que saldría de reconstruir el grafo. No agrega la arista al grafo.
     */
    public static Arista construirArista(Vuelo v, Grafo graph, int indice) {
        Arista edge = new Arista();

        if (v.getId() != null) {
            edge.id = v.getId().toString();
        } else {
            edge.id = v.getAeropuertoOrigen().getCodigo() + "-" +
                    v.getAeropuertoDestino().getCodigo() + "-" +
                    v.getFechaHoraSalida().toLocalTime().toString();
        }

        edge.origen = graph.nodos.get(v.getAeropuertoOrigen().getCodigo());
        edge.destino = graph.nodos.get(v.getAeropuertoDestino().getCodigo());

        edge.capacidad = v.getCapacidad() != null ? v.getCapacidad() : 0;

        int originOffset = v.getAeropuertoOrigen().getOffsetHorario();
        int destOffset   = v.getAeropuertoDestino().getOffsetHorario();

        LocalDateTime depUtc = v.getFechaHoraSalida().minusHours(originOffset);

        int depWall = v.getFechaHoraSalida().getHour() * 60 + v.getFechaHoraSalida().getMinute();
        int arrWall = v.getFechaHoraLlegada().getHour() * 60 + v.getFechaHoraLlegada().getMinute();
        int durMin = Math.floorMod((arrWall - destOffset * 60) - (depWall - originOffset * 60), 1440);

        LocalDateTime arrUtc = depUtc.plusMinutes(durMin);
        Duration utcDur = Duration.ofMinutes(durMin);

        edge.horaSalida     = depUtc;
        edge.horaLlegada       = arrUtc;
        edge.duracion          = utcDur;
        edge.costo              = durMin;
        edge.horaSalidaLocal = depUtc.toLocalTime();
        edge.duracionMinutos   = durMin;
        edge.minutoDelDiaSalida    = depUtc.getHour() * 60 + depUtc.getMinute();
        edge.indice               = indice;

        return edge;
    }

    /** Mapeo sin fragmentación (comportamiento previo exacto): delega con umbral infinito. */
    public List<LoteEnvio> mapearALotes(List<Envio> maletas) {
        return mapearALotes(maletas, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Mapea la demanda a lotes fragmentando AL NACER los envíos cuya cantidad supera {@code umbral}
     * (caso E1: cantidad &gt; capacidad de avión). Con {@code umbral = Integer.MAX_VALUE} nadie se
     * fragmenta (equivale al mapeo previo). El reparto es puro y determinista
     * ({@link FragmentadorEnvios#fragmentar}), imprescindible porque procesarBloque re-mapea la demanda
     * dos veces por bloque y ambas pasadas deben producir ids/cantidades idénticos.
     */
    public List<LoteEnvio> mapearALotes(List<Envio> maletas, int umbral, int maxSublotes) {
        List<LoteEnvio> out = new ArrayList<>();
        for (Envio m : maletas) {
            int offset = m.getAeropuertoOrigen().getOffsetHorario();
            LocalDateTime readyTimeUtc = m.getFechaHoraRegistro().minusHours(offset);
            LoteEnvio b = new LoteEnvio(
                    m.getIdEnvio(),
                    m.getCantidad(),
                    m.getPlazo(),
                    m.getAeropuertoOrigen().getCodigo(),
                    m.getAeropuertoDestino().getCodigo(),
                    readyTimeUtc
            );
            if (m.getCliente() != null) b.setClienteId(m.getCliente().getId());
            out.addAll(FragmentadorEnvios.fragmentar(b, umbral, maxSublotes));
        }
        return out;
    }
}
