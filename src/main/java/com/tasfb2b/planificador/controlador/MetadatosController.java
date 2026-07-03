package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.dto.datos.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.servicios.MetadatosDatosService;
import com.tasfb2b.planificador.servicios.PlanificadorService;
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

@RestController
@RequestMapping("/api/planificador")
public class MetadatosController {

    private final MetadatosDatosService datasetMetadata;
    private final PlanificadorService service;

    public MetadatosController(MetadatosDatosService datasetMetadata, PlanificadorService service) {
        this.datasetMetadata = datasetMetadata;
        this.service = service;
    }

    @GetMapping("/dataset/info")
    public ResponseEntity<DatosInfoResponse> datosInfo() {
        return ResponseEntity.ok(datasetMetadata.getDatosInfo());
    }

    @GetMapping("/aeropuertos")
    public ResponseEntity<Map<String, AeropuertoDTO>> aeropuertos() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(datasetMetadata.getAeropuertosInfo());
    }

    @GetMapping("/vuelos")
    public ResponseEntity<List<VueloBackend>> vuelos() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(datasetMetadata.getVuelosPlaneados());
    }

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
