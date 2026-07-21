package com.tasfb2b.planificador.controlador;

import com.tasfb2b.planificador.dto.datos.*;
import com.tasfb2b.planificador.dto.vuelos.*;
import com.tasfb2b.planificador.servicios.MetadatosDatosService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/planificador")
public class MetadatosController {

    private final MetadatosDatosService datasetMetadata;

    public MetadatosController(MetadatosDatosService datasetMetadata) {
        this.datasetMetadata = datasetMetadata;
    }

    @GetMapping("/dataset/info")
    public ResponseEntity<DatosInfoResponse> datosInfo() {
        return ResponseEntity.ok(datasetMetadata.getDatosInfo());
    }

    @GetMapping("/aeropuertos")
    public ResponseEntity<Map<String, AeropuertoDTO>> aeropuertos() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(datasetMetadata.getAeropuertosInfo());
    }

    @GetMapping("/vuelos")
    public ResponseEntity<List<VueloBackend>> vuelos() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(datasetMetadata.getVuelosPlaneados());
    }

    @GetMapping("/escenarios")
    public ResponseEntity<Map<String, Object>> catalogoEscenarios() {
        return ResponseEntity.ok(datasetMetadata.getCatalogoEscenarios());
    }
}
