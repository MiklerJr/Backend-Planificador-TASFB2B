package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.controlador.EscenarioController;
import com.tasfb2b.planificador.dto.vuelos.AltaVueloRequest;
import com.tasfb2b.planificador.excepcion.ParametroInvalidoException;
import com.tasfb2b.planificador.servicios.jobs.EstadoJob;
import com.tasfb2b.planificador.servicios.jobs.OperacionesEnVivoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Endpoint {@code POST /jobs/{jobId}/cargar-vuelos-txt}: carga masiva de planes de vuelo EN CALIENTE
 * desde TXT. Cada línea válida se encola por la tubería de agregar-vuelo; duplicados y líneas basura se
 * descartan POR LÍNEA (no abortan el lote). Mockea {@link PlanificadorService} (lookup del job) y
 * {@link OperacionesEnVivoService} (encolado request-side); el resto del flujo es real.
 */
class CargarVuelosTxtEndpointTest {

    private PlanificadorService service;
    private OperacionesEnVivoService operacionesEnVivo;
    private EscenarioController controller;

    @BeforeEach
    void setUp() {
        service = mock(PlanificadorService.class);
        operacionesEnVivo = mock(OperacionesEnVivoService.class);
        controller = new EscenarioController(service, operacionesEnVivo, null, null);
    }

    private static MultipartFile archivo(String contenido) {
        return new MockMultipartFile("archivos", "vuelos.txt", "text/plain",
                contenido.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void jobInexistenteDevuelve404() {
        when(service.getJob("nope")).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp = controller.cargarVuelosTxt(
                "nope", new MultipartFile[]{archivo("SPIM-SABE-04:00-08:30-0150")});

        assertEquals(404, resp.getStatusCode().value());
        verify(operacionesEnVivo, never()).solicitarAltaVuelo(any(), any());
    }

    @Test
    void jobTerminadoDevuelve409() {
        when(service.getJob("JOB1")).thenReturn(new EstadoJob("JOB1", "1", 1));
        when(operacionesEnVivo.solicitarAltaVuelo(eq("JOB1"), any())).thenReturn(false);   // job inactivo

        ResponseEntity<Map<String, Object>> resp = controller.cargarVuelosTxt(
                "JOB1", new MultipartFile[]{archivo("SPIM-SABE-04:00-08:30-0150")});

        assertEquals(409, resp.getStatusCode().value());
        assertEquals(false, resp.getBody().get("encolado"));
    }

    @Test
    void archivoMixtoEncolaValidosYDescartaResto() {
        when(service.getJob("JOB1")).thenReturn(new EstadoJob("JOB1", "1", 1));
        // 1º válido → encolado; 2º y 3º válidos pero duplicados → la tubería lanza 400 por línea.
        when(operacionesEnVivo.solicitarAltaVuelo(eq("JOB1"), any(AltaVueloRequest.class)))
                .thenReturn(true)
                .thenThrow(new ParametroInvalidoException("ya existe un vuelo con id VIDP-EKCH-1015"))
                .thenThrow(new ParametroInvalidoException("ya hay un alta encolada con id SPIM-SABE-0400"));

        String txt = String.join("\n",
                "** vuelos adicionales de la prueba",
                "SPIM-SABE-04:00-08:30-0150",     // nuevo → encolado
                "VIDP-EKCH-10:15-13:00-0150",     // duplicado del dataset → descartado
                "SPIM-SABE-04:00-08:30-0150",     // repetido en la cola → descartado
                "basura sin formato",             // línea inválida → descartada por el parser
                "ORIG-DEST-HH:MM-HH:MM-CAP");     // cabecera → ignorada

        ResponseEntity<Map<String, Object>> resp = controller.cargarVuelosTxt(
                "JOB1", new MultipartFile[]{archivo(txt)});

        assertEquals(202, resp.getStatusCode().value());
        Map<String, Object> body = resp.getBody();
        assertEquals(true, body.get("encolado"));
        assertEquals(1, body.get("encolados"));
        assertEquals(3, body.get("descartados"), "2 duplicados + 1 línea basura");
        assertEquals(3, ((List<?>) body.get("detalleDescartados")).size());
        verify(operacionesEnVivo, times(3)).solicitarAltaVuelo(eq("JOB1"), any());
    }

    @Test
    void sinArchivosLanza400() {
        when(service.getJob("JOB1")).thenReturn(new EstadoJob("JOB1", "1", 1));
        assertThrows(ParametroInvalidoException.class,
                () -> controller.cargarVuelosTxt("JOB1", new MultipartFile[0]));
    }

    @Test
    void archivoSoloComentariosLanza400() {
        when(service.getJob("JOB1")).thenReturn(new EstadoJob("JOB1", "1", 1));
        assertThrows(ParametroInvalidoException.class,
                () -> controller.cargarVuelosTxt("JOB1",
                        new MultipartFile[]{archivo("** solo comentarios\n// nada útil")}));
    }
}
