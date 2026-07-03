package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.configuracion.PlanificadorProperties;
import com.tasfb2b.planificador.dto.datos.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.modelo.datos.Vuelo;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

import static com.tasfb2b.planificador.utilidades.FormatoSimulacion.safe;
import static com.tasfb2b.planificador.utilidades.FormatoSimulacion.vueloFrontId;

@Service
public class MetadatosDatosService {

    static final int DEFAULT_DEMANDA_MAX_DIAS = 31;

    private final CargadorDatos cargadorDatos;
    private final int demandaMaxDias;

    @Autowired
    public MetadatosDatosService(CargadorDatos cargadorDatos, PlanificadorProperties props) {
        this.cargadorDatos = cargadorDatos;
        this.demandaMaxDias = (props != null && props.getConsulta() != null
                && props.getConsulta().getDemandaMaxDias() > 0)
                ? props.getConsulta().getDemandaMaxDias()
                : DEFAULT_DEMANDA_MAX_DIAS;
    }

    public MetadatosDatosService(CargadorDatos cargadorDatos) {
        this(cargadorDatos, null);
    }

    public Map<String, AeropuertoDTO> getAeropuertosInfo() {
        Map<String, AeropuertoDTO> info = new LinkedHashMap<>();
        for (Aeropuerto a : cargadorDatos.getAeropuertos()) {
            AeropuertoDTO dto = new AeropuertoDTO();
            dto.setCodigo(a.getCodigo());
            dto.setLatitud(a.getLatitud() != null ? a.getLatitud() : 0.0);
            dto.setLongitud(a.getLongitud() != null ? a.getLongitud() : 0.0);
            dto.setCapacidadAlmacen(a.getCapacidad());
            dto.setGmt(a.getOffsetHorario() != null ? a.getOffsetHorario().doubleValue() : 0.0);
            info.put(a.getCodigo(), dto);
        }
        return info;
    }

    public List<VueloBackend> getVuelosPlaneados() {
        List<Vuelo> vuelos = cargadorDatos.getVuelos();
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

    public DatosInfoResponse getDatosInfo() {
        LocalDateTime primera = cargadorDatos.getPrimeraVentana();
        LocalDateTime ultima  = cargadorDatos.getUltimaVentana();
        long diasDisponibles = 0L;
        if (primera != null && ultima != null) {
            diasDisponibles = java.time.Duration.between(primera, ultima).toDays();
            if (diasDisponibles < 1) diasDisponibles = 1; // mínimo 1 día si hay datos
        }
        DatosInfoResponse out = new DatosInfoResponse();
        out.setPrimeraVentana(primera != null ? primera.toString() : null);
        out.setUltimaVentana(ultima  != null ? ultima.toString()  : null);
        out.setDiasDisponibles(diasDisponibles);
        // totalMaletas queda por compatibilidad: historicamente equivale a filas/envios.
        out.setTotalMaletas(cargadorDatos.getTotalMaletas());
        out.setTotalEnvios(cargadorDatos.getTotalEnvios());
        out.setTotalMaletasIndividuales(cargadorDatos.getTotalMaletasIndividuales());
        out.setTotalAeropuertos(cargadorDatos.getAeropuertos().size());
        out.setTotalVuelos(cargadorDatos.getVuelos().size());
        return out;
    }

    public DemandaResumenResponse getDemandaResumen(LocalDateTime desde,
                                                    LocalDateTime hasta,
                                                    int top) {
        LocalDateTime primera = cargadorDatos.getPrimeraVentana();
        LocalDateTime ultima = cargadorDatos.getUltimaVentana();
        LocalDateTime inicio = desde != null ? desde : primera;
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

        for (CargadorDatos.DemandaAgrupada fila : cargadorDatos.agregarDemandaEnRango(inicio, fin)) {
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
