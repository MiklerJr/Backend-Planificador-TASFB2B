package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.config.PlanificadorProperties;
import com.tasfb2b.planificador.dto.dataset.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.model.dataset.Aeropuerto;
import com.tasfb2b.planificador.model.dataset.Vuelo;
import com.tasfb2b.planificador.util.DataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

import static com.tasfb2b.planificador.util.SimulacionFormat.safe;
import static com.tasfb2b.planificador.util.SimulacionFormat.vueloFrontId;

/**
 * Metadatos estáticos del dataset cargado en RAM (Tanda 2B: extraído de {@code PlanificadorService}).
 * Solo lectura sobre {@link DataLoader}: catálogo de aeropuertos y vuelos, rango temporal disponible
 * y resumen de demanda por ventana. Alimenta los endpoints de {@code MetadataController} sin tocar el
 * bucle de simulación.
 *
 * <p>El JSON de salida es idéntico al que producía {@code PlanificadorService}: estos métodos y sus
 * helpers se movieron tal cual.
 */
@Service
public class DatasetMetadataService {

    /** Default del span máximo (días) de {@code /demanda/resumen} si no hay config (constructor de tests). */
    static final int DEFAULT_DEMANDA_MAX_DIAS = 31;

    private final DataLoader dataLoader;
    /** Anti-OOM: span máximo admitido por {@code /demanda/resumen} (de {@code planificador.consulta}). */
    private final int demandaMaxDias;

    @Autowired
    public DatasetMetadataService(DataLoader dataLoader, PlanificadorProperties props) {
        this.dataLoader = dataLoader;
        this.demandaMaxDias = (props != null && props.getConsulta() != null
                && props.getConsulta().getDemandaMaxDias() > 0)
                ? props.getConsulta().getDemandaMaxDias()
                : DEFAULT_DEMANDA_MAX_DIAS;
    }

    /** Constructor sin config para tests (usa el default de span). */
    public DatasetMetadataService(DataLoader dataLoader) {
        this(dataLoader, null);
    }

    /**
     * Mapa estático de aeropuertos del dataset cargado. Pensado para que el
     * front cachee las coordenadas al arrancar la sesión y pueda dibujar
     * los bloques de forma incremental sin esperar a {@code /resultado}.
     */
    public Map<String, AeropuertoDTO> getAeropuertosInfo() {
        Map<String, AeropuertoDTO> info = new LinkedHashMap<>();
        for (Aeropuerto a : dataLoader.getAeropuertos()) {
            AeropuertoDTO dto = new AeropuertoDTO();
            dto.setCodigo(a.getCodigo());
            dto.setLatitud(a.getLatitud() != null ? a.getLatitud() : 0.0);
            dto.setLongitud(a.getLongitud() != null ? a.getLongitud() : 0.0);
            dto.setCapacidadAlmacen(a.getCapacidad());
            // gmt = el MISMO offset que usa el motor (horas enteras del dataset); el front lo emplea
            // para el reloj local y la conversión local→UTC, así comparten exactamente el mismo huso.
            dto.setGmt(a.getOffsetHorario() != null ? a.getOffsetHorario().doubleValue() : 0.0);
            info.put(a.getCodigo(), dto);
        }
        return info;
    }

    /**
     * Catálogo estático de vuelos planeados del dataset cargado (la red completa, ~2.866 vuelos).
     * Espejo de {@link #getAeropuertosInfo()}: pensado para que el front cachee la red al arrancar
     * la sesión y pre-dibuje TODAS las aristas sin esperar a {@code /resultado} (que solo llega al
     * final). Devuelve los horarios de plantilla base (sin desplazamiento de fecha); los horarios
     * reales por día llegan en los tramos UTC de cada bloque. {@code cargaAsignada} siempre 0: la
     * carga real es por bloque (ver {@code CargaVuelo} / {@code /jobs/{id}/vuelos/usados}).
     */
    public List<VueloBackend> getVuelosPlaneados() {
        List<Vuelo> vuelos = dataLoader.getVuelos();
        List<VueloBackend> out = new ArrayList<>(vuelos.size());
        for (Vuelo v : vuelos) {
            VueloBackend vb = new VueloBackend();
            vb.setId(vueloFrontId(v));
            vb.setOrigen(v.getOrigen());
            vb.setDestino(v.getDestino());
            vb.setFechaSalida(v.getFechaHoraSalida() != null ? v.getFechaHoraSalida().toString() : null);
            vb.setFechaLlegada(v.getFechaHoraLlegada() != null ? v.getFechaHoraLlegada().toString() : null);
            vb.setCapacidadMaxima(v.getCapacidad() != null ? v.getCapacidad() : 0);
            vb.setCargaAsignada(0);
            out.add(vb);
        }
        return out;
    }

    /**
     * Metadatos del dataset cargado (rango de fechas, días disponibles, total
     * de maletas). Útil para que el front valide {@code fechaInicio} contra
     * el rango antes de invocar {@code /escenario2/iniciar}.
     *
     * <p>Devuelve nulls en los campos de fecha si el dataset está vacío.
     */
    public DatasetInfoResponse getDatasetInfo() {
        LocalDateTime primera = dataLoader.getPrimeraVentana();
        LocalDateTime ultima  = dataLoader.getUltimaVentana();
        long diasDisponibles = 0L;
        if (primera != null && ultima != null) {
            diasDisponibles = java.time.Duration.between(primera, ultima).toDays();
            if (diasDisponibles < 1) diasDisponibles = 1; // mínimo 1 día si hay datos
        }
        DatasetInfoResponse out = new DatasetInfoResponse();
        out.setPrimeraVentana(primera != null ? primera.toString() : null);
        out.setUltimaVentana(ultima  != null ? ultima.toString()  : null);
        out.setDiasDisponibles(diasDisponibles);
        // totalMaletas queda por compatibilidad: historicamente equivale a filas/envios.
        out.setTotalMaletas(dataLoader.getTotalMaletas());
        out.setTotalEnvios(dataLoader.getTotalEnvios());
        out.setTotalMaletasIndividuales(dataLoader.getTotalMaletasIndividuales());
        out.setTotalAeropuertos(dataLoader.getAeropuertos().size());
        out.setTotalVuelos(dataLoader.getVuelos().size());
        return out;
    }

    public DemandaResumenResponse getDemandaResumen(LocalDateTime desde,
                                                    LocalDateTime hasta,
                                                    int top) {
        LocalDateTime primera = dataLoader.getPrimeraVentana();
        LocalDateTime ultima = dataLoader.getUltimaVentana();
        LocalDateTime inicio = desde != null ? desde : primera;
        // Anti-OOM (guarda de rango): si falta `hasta` o el span supera demanda-max-dias, se acota a
        // inicio + demanda-max-dias (y se reporta el rango efectivo). Con la agregación en SQL el peor
        // caso ya no agota el heap; la guarda acota el escaneo de BD. Se respeta el fin del dataset.
        LocalDateTime fin = hasta;
        if (inicio != null) {
            LocalDateTime topeSpan = inicio.plusDays(demandaMaxDias);
            LocalDateTime topeDataset = ultima != null ? ultima.plusMinutes(1) : null;
            if (fin == null) fin = topeDataset != null ? topeDataset : topeSpan;
            if (fin.isAfter(topeSpan)) fin = topeSpan;
        }
        int limite = Math.max(1, Math.min(top <= 0 ? 20 : top, 200));

        DemandaResumenResponse body = new DemandaResumenResponse();
        body.setDesde(inicio != null ? inicio.toString() : null);
        body.setHasta(fin != null ? fin.toString() : null);
        body.setTop(limite);

        if (inicio == null || fin == null || !inicio.isBefore(fin)) {
            body.setTotalEnvios(0);
            body.setTotalMaletas(0L);
            body.setPorOrigen(List.of());
            body.setPorDestino(List.of());
            body.setPorOD(List.of());
            return body;
        }

        Map<String, long[]> porOrigen = new HashMap<>();
        Map<String, long[]> porDestino = new HashMap<>();
        Map<String, long[]> porOd = new HashMap<>();
        long totalMaletas = 0L;
        long totalEnvios = 0L;

        // Agregación en BD por par O→D (≤ ~900 filas): no materializa los envíos del rango en RAM.
        for (DataLoader.DemandaAgrupada fila : dataLoader.agregarDemandaEnRango(inicio, fin)) {
            String origen = safe(fila.origen());
            String destino = safe(fila.destino());
            totalEnvios += fila.envios();
            totalMaletas += fila.maletas();
            acumularDemanda(porOrigen, origen, fila.envios(), fila.maletas());
            acumularDemanda(porDestino, destino, fila.envios(), fila.maletas());
            acumularDemanda(porOd, origen + "->" + destino, fila.envios(), fila.maletas());
        }

        body.setTotalEnvios((int) Math.min(totalEnvios, Integer.MAX_VALUE));
        body.setTotalMaletas(totalMaletas);
        body.setPorOrigen(demandaRows(porOrigen, limite));
        body.setPorDestino(demandaRows(porDestino, limite));
        body.setPorOD(demandaRows(porOd, limite));
        return body;
    }

    private static void acumularDemanda(Map<String, long[]> acc, String key, long envios, long maletas) {
        long[] stats = acc.computeIfAbsent(safe(key), k -> new long[2]);
        stats[0] += envios;
        stats[1] += maletas;
    }

    private static List<DemandaResumenResponse.DemandaRow> demandaRows(Map<String, long[]> acc, int limite) {
        List<DemandaResumenResponse.DemandaRow> rows = new ArrayList<>();
        acc.entrySet().stream()
                .sorted((a, b) -> {
                    int byMaletas = Long.compare(b.getValue()[1], a.getValue()[1]);
                    if (byMaletas != 0) return byMaletas;
                    return Long.compare(b.getValue()[0], a.getValue()[0]);
                })
                .limit(limite)
                .forEach(e -> rows.add(new DemandaResumenResponse.DemandaRow(
                        e.getKey(), e.getValue()[0], e.getValue()[1])));
        return rows;
    }
}
