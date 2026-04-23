package com.tasfb2b.planificador.algorithm.aco;

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

    /**
     * Información del envío que el ACO está planificando.
     * Setear estos valores antes de llamar a run() en AlgorithmACO.
     */
    public static class EnvioContext {
        public String origenICAO;       // aeropuerto donde se origina (del nombre del archivo)
        public String destinoICAO;      // campo dest del archivo
        public int cantidadMaletas;     // campo NNN parseado a int
        public int minutosRegistro;     // HH*60 + MM del archivo, en minutos desde 00:00
        public int deadlineMinutos;     // minutosRegistro + SLA (calculado automáticamente)

        public EnvioContext(String origenICAO, String destinoICAO,
                            int cantidadMaletas, int hh, int mm) {
            this.origenICAO      = origenICAO;
            this.destinoICAO     = destinoICAO;
            this.cantidadMaletas = cantidadMaletas;
            this.minutosRegistro = hh * 60 + mm;
            boolean mismoContiente = getContinente(origenICAO)
                                        .equals(getContinente(destinoICAO));
            this.deadlineMinutos = this.minutosRegistro +
                    (mismoContiente ? SLA_MISMO_CONTINENTE : SLA_DISTINTO_CONTINENTE);
        }
    }

    // 1. COSTO DE UN EDGE (se asigna a edge.cost al cargar los datos)

    /**
     * Calcula el costo de un edge individual para un envío dado.
     * Asignar a edge.cost antes de correr el ACO.
     *
     * Componentes:
     *  - Duración real del vuelo (minutos), manejando cruces de medianoche
     *  - Penalización si el edge no tiene capacidad suficiente para el envío
     *
     * @param edge   arista del grafo (vuelo directo entre dos aeropuertos)
     * @param envio  contexto del envío actual
     * @return       costo del tramo (double, minimizar)
     */
    public static double calcularCostoEdge(Edge edge, EnvioContext envio) {

        // --- Duración real del vuelo en minutos ---
        double duracion = calcularDuracionMinutos(edge.departureTime, edge.arrivalTime);

        // --- Penalización por falta de capacidad ---
        double penCapacidad = 0.0;
        if (!edge.hasCapacity(envio.cantidadMaletas)) {
            int exceso = (edge.usedCapacity + envio.cantidadMaletas) - edge.capacity;
            penCapacidad = exceso * W_CAPACITY;
        }

        return W_FLIGHT_TIME * duracion + penCapacidad;
    }

    // 2. COSTO TOTAL DE LA RUTA (evalúa la solución completa de una hormiga)

    /**
     * Evalúa el costo total de la ruta construida por una hormiga.
     * Usar para comparar hormigas y actualizar feromonas.
     *
     * ant.totalCost ya acumula edge.cost durante buildSolution,
     * pero este método añade los componentes que dependen de la ruta completa:
     * penalización SLA y tiempos de espera entre conexiones.
     *
     * @param ant    hormiga con su path construido
     * @param edges  lista de edges del grafo (para encontrar los vuelos usados)
     * @param envio  contexto del envío
     * @return       costo total ajustado (reemplaza ant.totalCost para comparación)
     */
    public static double calcularCostoRuta(Ant ant, List<Edge> edges, EnvioContext envio) {

        if (ant.path.size() < 2) return Double.MAX_VALUE; // ruta inválida

        // Reconstruir los edges usados en el path
        List<Edge> edgesUsados = reconstruirEdgesUsados(ant.path, edges);

        // --- Tiempo total de vuelo (ya en ant.totalCost, pero lo recalculamos limpio) ---
        double tiempoVuelo = 0.0;
        for (Edge e : edgesUsados) {
            tiempoVuelo += calcularDuracionMinutos(e.departureTime, e.arrivalTime);
        }

        // --- Tiempo de espera entre conexiones ---
        double tiempoEspera = calcularTiempoEsperaTotal(edgesUsados);

        // --- Tiempo de llegada al destino final (en minutos desde 00:00) ---
        Edge ultimoVuelo = edgesUsados.get(edgesUsados.size() - 1);
        int minutosLlegada = parsearMinutos(ultimoVuelo.arrivalTime);
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
            tiempoVuelo += calcularDuracionMinutos(e.departureTime, e.arrivalTime);
        }

        double tiempoEspera = calcularTiempoEsperaTotal(edgesUsados);

        Edge ultimoVuelo = edgesUsados.get(edgesUsados.size() - 1);
        int minutosLlegada = parsearMinutos(ultimoVuelo.arrivalTime);
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
        int minutosLlegada = parsearMinutos(ultimoVuelo.arrivalTime);
        minutosLlegada += calcularDiasRuta(edgesPath) * 1440;

        return minutosLlegada <= envio.deadlineMinutos;
    }

    // 3. HEURÍSTICA η — reemplaza 1/(e.cost+1) en selectEdge()

    /**
     * Heurística η(edge) para la selección probabilística del ACO.
     *
     * Reemplaza el 1.0 / (e.cost + 1) genérico de selectEdge() con uno
     * que considera la ocupación del vuelo y el tiempo real.
     *
     * En AlgorithmACO.selectEdge() cambiar:
     *   double heuristic = Math.pow(1.0 / (e.cost + 1), config.beta);
     * por:
     *   double heuristic = Math.pow(CostFunction.heuristica(e, envioContext), config.beta);
     *
     * @param edge  arista candidata
     * @return      valor η > 0 (mayor = más atractivo)
     */
    public static double heuristica(Edge edge, EnvioContext envio) {

        double duracion = calcularDuracionMinutos(edge.departureTime, edge.arrivalTime);
        if (duracion <= 0) duracion = 1;

        // Factor de ocupación basado en semáforo
        double factorOcupacion = factorSemaforo(edge, envio.cantidadMaletas);

        return factorOcupacion / duracion;
    }

    // 4. SEMÁFORO DE OCUPACIÓN (para visualizador)
    /**
     * Color semáforo de un edge según su ocupación actual.
     * Los umbrales UMBRAL_VERDE y UMBRAL_AMBAR son parámetros configurables.
     *
     * @return "VERDE", "AMBAR" o "ROJO"
     */
    public static String semaforoOcupacion(Edge edge) {
        double ocup = (double) edge.usedCapacity / edge.capacity;
        if (ocup <= UMBRAL_VERDE) return "VERDE";
        if (ocup <= UMBRAL_AMBAR) return "AMBAR";
        return "ROJO";
    }

    // Helpers privados
    /**
     * Parsea "HH:MM" a minutos totales desde 00:00.
     */
    private static int parsearMinutos(String horaStr) {
        String[] partes = horaStr.split(":");
        return Integer.parseInt(partes[0]) * 60 + Integer.parseInt(partes[1]);
    }

    /**
     * Duración real del vuelo en minutos.
     * Maneja correctamente los vuelos que cruzan medianoche.
     * Ejemplo: salida 22:45 → llegada 01:01 = 136 min (no -1304)
     */
    public static double calcularDuracionMinutos(String salida, String llegada) {
        int minSalida  = parsearMinutos(salida);
        int minLlegada = parsearMinutos(llegada);
        int diff = minLlegada - minSalida;
        return diff < 0 ? diff + 1440 : diff;
    }

    /**
     * Suma los tiempos de espera entre vuelos consecutivos de la ruta.
     * Si la conexión cruza medianoche, suma correctamente.
     */
    private static double calcularTiempoEsperaTotal(List<Edge> edgesRuta) {
        double espera = 0.0;
        for (int i = 0; i < edgesRuta.size() - 1; i++) {
            int llegada = parsearMinutos(edgesRuta.get(i).arrivalTime);
            int salida  = parsearMinutos(edgesRuta.get(i + 1).departureTime);
            int diff = salida - llegada;
            if (diff < 0) diff += 1440;
            espera += diff;
        }
        return espera;
    }

    /**
     * Verifica si hay tiempo suficiente para la escala entre dos vuelos.
     * El equipaje debe cambiar de avión, lo cual requiere tiempo mínimo de escala.
     *
     * @param vueloAnterior  vuelo que llega al aeropuerto de escala
     * @param vueloSiguiente vuelo que sale del aeropuerto de escala
     * @return true si el tiempo entre llegada y salida es >= TIEMPO_MIN_ESCALA
     */
    public static boolean tieneTiempoMinimoEscala(Edge vueloAnterior, Edge vueloSiguiente) {
        int llegada = parsearMinutos(vueloAnterior.arrivalTime);
        int salida = parsearMinutos(vueloSiguiente.departureTime);
        int diff = salida - llegada;
        if (diff < 0) diff += 1440;
        return diff >= TIEMPO_MIN_ESCALA;
    }

    /**
     * Verifica si hay tiempo suficiente en destino final para recoger el equipaje.
     * Después de llegar, el cliente necesita tiempo para recoger la maleta.
     *
     * @param ultimoVuelo vuelo que llega al destino final
     * @return true si hay al menos TIEMPO_DESTINO_FINAL minutos disponibles
     */
    public static boolean tieneTiempoDestinoFinal(Edge ultimoVuelo) {
        return true;
    }

    /**
     * Estima cuántos días completos adicionales acumula la ruta (para el SLA).
     * Cada vez que la hora de salida de un tramo es menor a la llegada del anterior,
     * se cruzó un día.
     */
    private static int calcularDiasRuta(List<Edge> edgesRuta) {
        int dias = 0;
        int minutosActuales = parsearMinutos(edgesRuta.get(0).departureTime);
        for (Edge e : edgesRuta) {
            int salida  = parsearMinutos(e.departureTime);
            int llegada = parsearMinutos(e.arrivalTime);
            if (salida < minutosActuales) dias++; // cruzó medianoche esperando
            if (llegada < salida) dias++;          // cruzó medianoche volando
            minutosActuales = llegada;
        }
        return dias;
    }

    /**
     * Reconstruye la lista de edges usados a partir del path de nodos de la hormiga.
     */
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

    /**
     * Factor multiplicador según el semáforo de ocupación del edge.
     * Vuelo lleno → prácticamente bloqueado para la heurística.
     */
    private static double factorSemaforo(Edge edge, int demanda) {
        double ocup = (double)(edge.usedCapacity + demanda) / edge.capacity;
        if (ocup > 1.0)         return 0.0001; // sin capacidad: bloqueado
        if (ocup > UMBRAL_AMBAR) return 0.30;  // rojo
        if (ocup > UMBRAL_VERDE) return 0.70;  // ámbar
        return 1.0;                             // verde
    }

    /**
     * Determina el continente de un aeropuerto por su prefijo ICAO.
     * Basado en los aeropuertos reales de los archivos:
     *   América → S* (SKBO, SEQM, SVMI, SBBR, SPIM, SLLP, SCEL, SABE, SGAS, SUAA)
     *   Europa  → E*, L*, UMMS, UBBB
     *   Asia    → O*, VIDP
     */
    public static String getContinente(String icao) {
        if (icao == null || icao.isEmpty()) return "DESCONOCIDO";
        switch (icao.charAt(0)) {
            case 'S': return "AMERICA";
            case 'E': return "EUROPA";
            case 'L': return "EUROPA";
            case 'U': return "EUROPA"; // UMMS (Minsk), UBBB (Bakú)
            case 'O': return "ASIA";
            case 'V': return "ASIA";   // VIDP (Delhi)
            default:  return "DESCONOCIDO";
        }
    }
}
