package com.tasfb2b.planificador.controller;

import com.tasfb2b.planificador.dto.dataset.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.services.DatasetMetadataService;
import com.tasfb2b.planificador.services.PlanificadorService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Metadatos estáticos del dataset y catálogos para el front. Todas las rutas cuelgan de
 * {@code /api/planificador}. CORS lo aporta el {@code CorsFilter} global de
 * {@code PlanificadorApplication} (sin {@code @CrossOrigin} por controller).
 */
@RestController
@RequestMapping("/api/planificador")
public class MetadataController {

    // Los metadatos del dataset (info/aeropuertos/vuelos/demanda) los sirve DatasetMetadataService;
    // PlanificadorService solo queda para el catálogo de escenarios.
    private final DatasetMetadataService datasetMetadata;
    private final PlanificadorService service;

    public MetadataController(DatasetMetadataService datasetMetadata, PlanificadorService service) {
        this.datasetMetadata = datasetMetadata;
        this.service = service;
    }

    /**
     * Metadatos del dataset cargado (rango temporal disponible, conteos).
     * Permite al front validar {@code fechaInicio} antes de enviar el job:
     * si la fecha está fuera de {@code [primeraVentana, ultimaVentana]} el
     * backend la ignora silenciosamente.
     */
    @GetMapping("/dataset/info")
    public ResponseEntity<DatasetInfoResponse> datasetInfo() {
        return ResponseEntity.ok(datasetMetadata.getDatasetInfo());
    }

    /**
     * Mapa estático de aeropuertos del dataset cargado:
     * {@code {[codigo]: {codigo, latitud, longitud, capacidadAlmacen}}}. Pensado para que el
     * front lo cachee al cargar la app y dibuje bloques incrementalmente
     * sin esperar a {@code /resultado}. No cambia en runtime.
     */
    @GetMapping("/aeropuertos")
    public ResponseEntity<Map<String, AeropuertoDTO>> aeropuertos() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(datasetMetadata.getAeropuertosInfo());
    }

    /**
     * Catálogo estático de vuelos planeados del dataset (la red completa, ~2.866 vuelos):
     * lista de {@code {id, origen, destino, fechaSalida, fechaLlegada, capacidadMaxima,
     * cargaAsignada}}. Espejo de {@code /aeropuertos}: pensado para que el front lo cachee al
     * cargar la app y pre-dibuje TODAS las aristas de la red sin esperar a {@code /resultado}.
     * Horarios de plantilla base; los reales por día llegan en los tramos de cada bloque.
     * {@code cargaAsignada} siempre 0 (la carga real es por bloque). No cambia en runtime.
     */
    @GetMapping("/vuelos")
    public ResponseEntity<List<VueloBackend>> vuelos() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(datasetMetadata.getVuelosPlaneados());
    }

    /**
     * Catálogo de escenarios disponibles para el front. Devuelve los valores
     * por defecto (Sa, Ta, K, umbrales), una descripción human-readable y la
     * lista de motores soportados.
     *
     * <p>El cuerpo lo construye {@link PlanificadorService#getCatalogoEscenarios()}:
     * el controller solo delega.
     */
    @GetMapping("/escenarios")
    public ResponseEntity<Map<String, Object>> catalogoEscenarios() {
        return ResponseEntity.ok(service.getCatalogoEscenarios());
    }

    @GetMapping("/demanda/resumen")
    public ResponseEntity<DemandaResumenResponse> demandaResumen(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta,
            @RequestParam(defaultValue = "20") int top) {
        return ResponseEntity.ok(datasetMetadata.getDemandaResumen(desde, hasta, top));
    }
}
