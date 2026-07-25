package com.tasfb2b.planificador.configuracion;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "planificador")
public class PlanificadorProperties {

    private Scenario     scenario     = new Scenario();
    private Alns         alns         = new Alns();
    private Objetivo     objetivo     = new Objetivo();
    private Backlog      backlog      = new Backlog();
    private StorageAware storageAware = new StorageAware();
    private AlertaColapso alertaColapso = new AlertaColapso();
    private Consulta     consulta     = new Consulta();
    private Cache        cache        = new Cache();
    private Operativo    operativo    = new Operativo();
    private Fragmentacion fragmentacion = new Fragmentacion();
    private Memoria      memoria      = new Memoria();

    @Data
    public static class Memoria {
        private int purgaOcupacionCadaBloques = 20;
        private int purgaOcupacionRetencionDias = 7;
    }

    @Data
    public static class Fragmentacion {
        private boolean habilitada = true;
        private int maxMaletasPorSublote = 0;
        private int maxSublotes = 64;
    }

    @Data
    public static class Operativo {
        private int tiempoMinEscalaMinutos = 10;

        private int tiempoRecojoDestinoMinutos = 15;
    }

    @Data
    public static class Scenario {
        private int saMinutos = 10;

        private int taSegundos = 0;
        private int kDefault1 = 1;
        private int kDefault2 = 14;
        private int kDefault3 = 144;
        private double umbralColapso = 0.20;
        private int umbralColapsoBacklog = 8000;
        private boolean simularTiempoReal1 = true;

        private int operacionHoras = 24;
        private boolean simularTiempoReal2 = true;
        private boolean simularTiempoReal3 = false;
        private boolean prewarmSkeletons = true;
        private int maxVentanas = 0;
        private int maxVentanasColapso = 0;

        private int maxBloquesBuffer = 60;

        private int maxAsignacionesBuffer = 0;

        private int maxJobsEnMemoria = 3;

        public int getMaxVentanas() {
            return maxVentanas;
        }

        public void setMaxVentanas(int maxVentanas) {
            this.maxVentanas = maxVentanas;
        }
    }

    @Data
    public static class Alns {
        private int iteracionesBase = 9;
        private int iteracionesCercaColapso = 25;
        private double destroyFactor = 0.20;
        private double initialTemp = 500.0;
        private double coolingRate = 0.85;
        private double minTemp = 0.1;
        private int minBlockSize = 3;
        private int segmentLength = 3;
        private double reactionFactor = 0.15;
        private List<String> operadoresDestroy = List.of("capacity", "worst-route");
    }

    @Data
    public static class Objetivo {
        private double pesoTransit = 1.0;
        private double pesoTarde = 5000.0;
        private double pesoUsoAlmacen = 50.0;
        private double pesoUsoVuelo = 20.0;
    }

    @Data
    public static class Backlog {
        private int maxSize = 10000;
        private boolean purgarVencidas = true;
        private double umbralReplanificacionSlack = 0.10;
        private int maxReplanificacionesPorBloque = 20;
        private int maxReprocesoPorBloque = 0;
    }

    @Data
    public static class AlertaColapso {
        private double almacenAmbar = 0.85;
        private double almacenRojo = 0.95;
        private double slaRestanteAmbar = 0.25;
        private double slaRestanteRojo = 0.10;
    }

    @Data
    public static class Consulta {
        private int maxFilasPagina = 5000;
    }

    @Data
    public static class Cache {
        private String skeletonFile = "data/skeleton-cache.bin";
    }

    @Data
    public static class StorageAware {
        private double reservaAlmacenBase = 0.15;
        private double umbralHubPico = 0.55;
        private double precioHubExponente = 1.7;
        private int reSeedSlice = 256;
    }

}
