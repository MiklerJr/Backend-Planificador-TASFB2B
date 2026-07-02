package com.tasfb2b.planificador.algorithm.aco;

import com.tasfb2b.planificador.algorithm.grafo.Edge;
import com.tasfb2b.planificador.algorithm.grafo.Node;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;


public class CostFunction {

    // Parámetros de pesos — configura estos según experimentación numérica
    public static double W_SLA_VIOLATION  = 10000.0; // penalización por violar plazo
    public static double W_FLIGHT_TIME    =     1.0; // minimizar duración del vuelo
    public static double W_WAIT_TIME      =     2.0; // penalizar espera en escala
    public static double W_CAPACITY       =   500.0; // penalizar sobrecarga de vuelo

    // Umbrales semáforo de ocupación (parámetros configurables)
    public static double UMBRAL_VERDE = 0.70;
    public static double UMBRAL_AMBAR = 0.90;

    // SLA en minutos según el enunciado
    public static final int SLA_MISMO_CONTINENTE    = 1440; // 1 día
    public static final int SLA_DISTINTO_CONTINENTE = 2880; // 2 días

    // Restricciones de tiempo de escala
    public static final int TIEMPO_MIN_ESCALA = 10;      // minutos mínimos entre vuelos (escala)
    public static final int TIEMPO_DESTINO_FINAL = 10;   // minutos antes de recoger en destino

    // Contexto del envío actual (se setea antes de correr el ACO)
    public static class EnvioContext {
        public String origenICAO;       // aeropuerto donde se origina (del nombre del archivo)
        public String destinoICAO;      // campo dest del archivo
        public int cantidadMaletas;     // campo NNN parseado a int
        public int minutosRegistro;     // HH*60 + MM del archivo, en minutos desde 00:00
        public int deadlineMinutos;     // minutosRegistro + SLA (calculado automáticamente)
        public int offsetOrigen;      // offset GMT del aeropuerto origen (horas)

        public EnvioContext(String origenICAO, String destinoICAO,
                            int cantidadMaletas, int hh, int mm) {
            this(origenICAO, destinoICAO, cantidadMaletas, hh, mm, 0);
        }

        public EnvioContext(String origenICAO, String destinoICAO,
                            int cantidadMaletas, int hh, int mm, int offsetOrigen) {
            this.origenICAO      = origenICAO;
            this.destinoICAO     = destinoICAO;
            this.cantidadMaletas = cantidadMaletas;
            this.offsetOrigen    = offsetOrigen;

            int minutosLocal = hh * 60 + mm;
            int minutosUtc = minutosLocal - (offsetOrigen * 60);
            if (minutosUtc >= 24 * 60) minutosUtc -= 24 * 60;
            if (minutosUtc < 0) minutosUtc += 24 * 60;

            this.minutosRegistro = minutosUtc;
            boolean mismoContiente = getContinente(origenICAO)
                                        .equals(getContinente(destinoICAO));
            this.deadlineMinutos = this.minutosRegistro +
                    (mismoContiente ? SLA_MISMO_CONTINENTE : SLA_DISTINTO_CONTINENTE);
        }
    }

    // 1. COSTO DE UN EDGE (se asigna a edge.cost al cargar los datos)
    public static double calcularCostoEdge(Edge edge, EnvioContext envio) {

        // --- Duración real del vuelo en minutos ---
        double duracion = calcularDuracionMinutos(edge.departureTime.toString(), edge.arrivalTime.toString());

        // --- Penalización por falta de capacidad ---
        double penCapacidad = 0.0;
        if (!edge.hasCapacity(envio.cantidadMaletas)) {
            int exceso = (edge.usedCapacity + envio.cantidadMaletas) - edge.capacity;
            penCapacidad = exceso * W_CAPACITY;
        }

        return W_FLIGHT_TIME * duracion + penCapacidad;
    }

    // 2. COSTO TOTAL DE LA RUTA (evalúa la solución completa de una hormiga)
    public static double calcularCostoRuta(Ant ant, List<Edge> edges, EnvioContext envio) {

        if (ant.path.size() < 2) return Double.MAX_VALUE; // ruta inválida

        // Reconstruir los edges usados en el path
        List<Edge> edgesUsados = reconstruirEdgesUsados(ant.path, edges);

        // --- Tiempo total de vuelo (ya en ant.totalCost, pero lo recalculamos limpio) ---
        double tiempoVuelo = 0.0;
        for (Edge e : edgesUsados) {
            tiempoVuelo += calcularDuracionMinutos(e.departureTime.toString(), e.arrivalTime.toString());
        }

        // --- Tiempo de espera entre conexiones ---
        double tiempoEspera = calcularTiempoEsperaTotal(edgesUsados);

        // --- Tiempo de llegada al destino final (en minutos desde 00:00) ---
        Edge ultimoVuelo = edgesUsados.get(edgesUsados.size() - 1);
        int minutosLlegada = getMinutosLlegada(ultimoVuelo);
        
        // Si la ruta tiene escalas que cruzan días, acumular días completos
        minutosLlegada += calcularDiasRuta(edgesUsados) * 1440;

        // --- Penalización SLA ---
        double penSLA = 0.0;
        int retraso = minutosLlegada - envio.deadlineMinutos;
        if (retraso > 0) {
            penSLA = retraso * W_SLA_VIOLATION;
        }

        // --- Penalización capacidad por toda la ruta ---
        double penCapacidad = 0.0;
        for (Edge e : edgesUsados) {
            if (!e.hasCapacity(envio.cantidadMaletas)) {
                int exceso = (e.usedCapacity + envio.cantidadMaletas) - e.capacity;
                penCapacidad += exceso * W_CAPACITY;
            }
        }

        // --- Penalización almacenamiento aeropuerto ---
        double penAlmacen = 0.0;
        for (int i = 1; i < ant.path.size() - 1; i++) {
            Node nodo = ant.path.get(i);
            if (nodo.storageUsed > nodo.storageCapacity) {
                int exceso = nodo.storageUsed - nodo.storageCapacity;
                penAlmacen += exceso * W_CAPACITY;
            }
        }

        return penSLA
             + W_FLIGHT_TIME * tiempoVuelo
             + W_WAIT_TIME   * tiempoEspera
             + penCapacidad
             + penAlmacen;
    }

    public static double calcularCostoRuta(Ant ant, List<Edge> edges, List<Edge> edgesPath, EnvioContext envio) {

        if (ant.path.size() < 2) return Double.MAX_VALUE;

        List<Edge> edgesUsados = (edgesPath != null && !edgesPath.isEmpty()) ? edgesPath : reconstruirEdgesUsados(ant.path, edges);

        double tiempoVuelo = 0.0;
        for (Edge e : edgesUsados) {
            tiempoVuelo += calcularDuracionMinutos(e.departureTime.toString(), e.arrivalTime.toString());
        }

        double tiempoEspera = calcularTiempoEsperaTotal(edgesUsados);

        Edge ultimoVuelo = edgesUsados.get(edgesUsados.size() - 1);
        int minutosLlegada = getMinutosLlegada(ultimoVuelo);
        
        minutosLlegada += calcularDiasRuta(edgesUsados) * 1440;

        double penSLA = 0.0;
        int retraso = minutosLlegada - envio.deadlineMinutos;
        if (retraso > 0) {
            penSLA = retraso * W_SLA_VIOLATION;
        }

        double penCapacidad = 0.0;
        for (Edge e : edgesUsados) {
            if (!e.hasCapacity(envio.cantidadMaletas)) {
                int exceso = (e.usedCapacity + envio.cantidadMaletas) - e.capacity;
                penCapacidad += exceso * W_CAPACITY;
            }
        }

        double penAlmacen = 0.0;
        for (int i = 1; i < ant.path.size() - 1; i++) {
            Node nodo = ant.path.get(i);
            if (nodo.storageUsed > nodo.storageCapacity) {
                int exceso = nodo.storageUsed - nodo.storageCapacity;
                penAlmacen += exceso * W_CAPACITY;
            }
        }

        return penSLA
             + W_FLIGHT_TIME * tiempoVuelo
             + W_WAIT_TIME   * tiempoEspera
             + penCapacidad
             + penAlmacen;
    }

    public static boolean cumpleRestriccionesDuras(Ant ant, List<Edge> edgesPath, EnvioContext envio) {
        if (ant == null || ant.path == null || ant.path.size() < 2) {
            return false;
        }
        if (edgesPath == null || edgesPath.isEmpty()) {
            return false;
        }
        Node ultimoNodo = ant.path.get(ant.path.size() - 1);
        if (!envio.destinoICAO.equals(ultimoNodo.code)) {
            return false;
        }

        for (Edge e : edgesPath) {
            if (!e.hasCapacity(envio.cantidadMaletas)) {
                return false;
            }
        }

        for (int i = 1; i < ant.path.size() - 1; i++) {
            Node nodo = ant.path.get(i);
            if (nodo.storageUsed > nodo.storageCapacity) {
                return false;
            }
        }

        Edge ultimoVuelo = edgesPath.get(edgesPath.size() - 1);
        int minutosLlegada = getMinutosLlegada(ultimoVuelo);
        minutosLlegada += calcularDiasRuta(edgesPath) * 1440;

        return minutosLlegada <= envio.deadlineMinutos;
    }

    // 3. HEURÍSTICA η — reemplaza 1/(e.cost+1) en selectEdge()
    public static double heuristica(Edge edge, EnvioContext envio, Ant hormiga) {

        // 1. RESTRICCIÓN DURA: ¿Tengo los 10 min de escala?
        if (hormiga.horaLlegadaActual != null) {
            long diffMinutos = Duration.between(hormiga.horaLlegadaActual, edge.departureTime).toMinutes();
            if (diffMinutos < 0) diffMinutos += 1440; // Manejo cruce medianoche
            if (diffMinutos < TIEMPO_MIN_ESCALA) {
                return 0.000001; // No hay tiempo para el transbordo, vuelo bloqueado.
            }
        }

        // 2. Duración del vuelo
        double duracion = calcularDuracionMinutos(edge.departureTime.toString(), edge.arrivalTime.toString());
        if (duracion <= 0) duracion = 1;

        // 3. Capacidad del Avión (Semáforo existente)
        double factorAvion = factorSemaforo(edge, envio.cantidadMaletas);

        // NUEVO: 4. Capacidad del Almacén Destino
        Node destino = edge.to;
        double ocupacionAlmacen = (double) (destino.storageUsed + envio.cantidadMaletas) / destino.storageCapacity;
        double factorAlmacen = 1.0;

        if (ocupacionAlmacen > 1.0) {
            factorAlmacen = 0.001; // Almacén colapsado: penalización extrema
        } else if (ocupacionAlmacen > UMBRAL_AMBAR) {
            factorAlmacen = 0.30;  // Almacén en peligro: evitar si es posible
        } else if (ocupacionAlmacen > UMBRAL_VERDE) {
            factorAlmacen = 0.70;  // Almacén llenándose
        }

        // Retornar la heurística combinada
        return (factorAvion * factorAlmacen) / duracion;
    }

    // 4. SEMÁFORO DE OCUPACIÓN (para visualizador)
    public static String semaforoOcupacion(Edge edge) {
        double ocup = (double) edge.usedCapacity / edge.capacity;
        if (ocup <= UMBRAL_VERDE) return "VERDE";
        if (ocup <= UMBRAL_AMBAR) return "AMBAR";
        return "ROJO";
    }

    // Helpers privados
    private static int parsearMinutos(String horaStr) {
        if (horaStr == null) return 0;
        // Si viene de LocalDateTime.toString() ("2026-01-01T19:00") tomar la parte
        // después de la 'T' para quedarnos con "HH:MM".
        int t = horaStr.indexOf('T');
        String hhmm = t >= 0 ? horaStr.substring(t + 1) : horaStr;
        String[] partes = hhmm.split(":");
        return Integer.parseInt(partes[0]) * 60 + Integer.parseInt(partes[1]);
    }

    public static double calcularDuracionMinutos(String salida, String llegada) {
        int minSalida  = parsearMinutos(salida);
        int minLlegada = parsearMinutos(llegada);
        int diff = minLlegada - minSalida;
        return diff < 0 ? diff + 1440 : diff;
    }

    public static double calcularDuracionMinutos(LocalDateTime salida, LocalDateTime llegada) {
        Duration d = Duration.between(salida, llegada);
        if (d.isNegative() || d.isZero()) {
            d = d.plusDays(1);
        }
        return d.toMinutes();
    }

    public static int hhmmAMinutos(String horaStr) {
        return parsearMinutos(horaStr);
    }

    private static double calcularTiempoEsperaTotal(List<Edge> edgesRuta) {
        double espera = 0.0;
        for (int i = 0; i < edgesRuta.size() - 1; i++) {
            Edge anterior = edgesRuta.get(i);
            Edge siguiente = edgesRuta.get(i + 1);
            long diff = Duration.between(anterior.arrivalTime, siguiente.departureTime).toMinutes();
            if (diff < 0) diff += 1440;
            espera += diff;
        }
        return espera;
    }

    public static int calcularTiempoEsperaMinutos(Edge anterior, Edge siguiente) {
        long diff = Duration.between(anterior.arrivalTime, siguiente.departureTime).toMinutes();
        if (diff < 0) diff += 1440;
        return (int) diff;
    }

    // Métodos que usan arrivalTime/departureTime como LocalDateTime
    public static int getMinutosLlegada(Edge ultimoVuelo) {
        LocalDateTime arrival = ultimoVuelo.arrivalTime;
        return arrival.getHour() * 60 + arrival.getMinute();
    }

    public static boolean tieneTiempoMinimoEscala(Edge vueloAnterior, Edge vueloSiguiente) {
        long diff = Duration.between(vueloAnterior.arrivalTime, vueloSiguiente.departureTime).toMinutes();
        if (diff < 0) diff += 1440;
        return diff >= TIEMPO_MIN_ESCALA;
    }

    public static int getMinutosDesdeMednight(LocalDateTime dt) {
        return dt.getHour() * 60 + dt.getMinute();
    }

    private static int calcularDiasRuta(List<Edge> edgesRuta) {
        LocalDateTime inicio = edgesRuta.get(0).departureTime;
        LocalDateTime fin = edgesRuta.get(edgesRuta.size() - 1).arrivalTime;
        return (int) java.time.temporal.ChronoUnit.DAYS.between(inicio.toLocalDate(), fin.toLocalDate());
    }

    private static List<Edge> reconstruirEdgesUsados(List<Node> path, List<Edge> todosEdges) {
        List<Edge> usados = new java.util.ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            String fromCode = path.get(i).code;
            String toCode   = path.get(i + 1).code;
            todosEdges.stream()
                .filter(e -> e.from.code.equals(fromCode) && e.to.code.equals(toCode))
                .findFirst()
                .ifPresent(usados::add);
        }
        return usados;
    }

    private static double factorSemaforo(Edge edge, int demanda) {
        double ocup = (double)(edge.usedCapacity + demanda) / edge.capacity;
        if (ocup > 1.0)         return 0.0001; // sin capacidad: bloqueado
        if (ocup > UMBRAL_AMBAR) return 0.30;  // rojo
        if (ocup > UMBRAL_VERDE) return 0.70;  // ámbar
        return 1.0;                             // verde
    }

    public static String getContinente(String icao) {
        if (icao == null || icao.isEmpty()) return "DESCONOCIDO";
        switch (icao.charAt(0)) {
            case 'S': return "AMERICA";
            case 'E': return "EUROPA";
            case 'L': return "EUROPA";
            case 'U': return "EUROPA";
            case 'O': return "ASIA";
            case 'V': return "ASIA";
            default:  return "DESCONOCIDO";
        }
    }
}
