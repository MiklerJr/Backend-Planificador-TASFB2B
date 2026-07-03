package com.tasfb2b.planificador.servicios;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistencia de la caché de esqueletos (pre-warm Fase T) entre reinicios. Cubre el contrato de
 * seguridad ante cambios de dataset: el archivo solo se carga si la huella coincide; corrupto o
 * truncado se ignora sin excepción (degrada al comportamiento previo: caché fría).
 */
class AlmacenCacheEsqueletosTest {

    private static final String HUELLA = "huella-dataset-a";

    private static Map<Long, List<int[]>> cacheEjemplo() {
        return Map.of(
                42L, List.of(new int[]{3, 7, 12}, new int[]{5}),
                -9L, List.of(new int[]{}),
                7L, List.of(new int[]{1, 2}));
    }

    @Test
    void roundTripGuardaYRecuperaLaCacheCompleta(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("skeleton-cache.bin");
        Map<Long, List<int[]>> original = cacheEjemplo();

        AlmacenCacheEsqueletos.escribir(archivo, HUELLA, original);
        Map<Long, List<int[]>> leida = AlmacenCacheEsqueletos.leerSiCoincide(archivo, HUELLA);

        assertEquals(original.keySet(), leida.keySet());
        for (Long clave : original.keySet()) {
            List<int[]> esperados = original.get(clave), obtenidos = leida.get(clave);
            assertEquals(esperados.size(), obtenidos.size(), "nº de esqueletos de la clave " + clave);
            for (int i = 0; i < esperados.size(); i++) {
                assertArrayEquals(esperados.get(i), obtenidos.get(i));
            }
        }
    }

    @Test
    void huellaDistintaDescartaElArchivo(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("skeleton-cache.bin");
        AlmacenCacheEsqueletos.escribir(archivo, HUELLA, cacheEjemplo());

        assertTrue(AlmacenCacheEsqueletos.leerSiCoincide(archivo, "huella-dataset-b").isEmpty(),
                "esqueletos de otro dataset (p. ej. ingesta de por medio) no deben cargarse");
    }

    @Test
    void archivoCorruptoOTruncadoSeIgnoraSinExcepcion(@TempDir Path dir) throws Exception {
        Path basura = dir.resolve("basura.bin");
        Files.write(basura, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertTrue(AlmacenCacheEsqueletos.leerSiCoincide(basura, HUELLA).isEmpty(), "magic desconocido");

        Path truncado = dir.resolve("truncado.bin");
        AlmacenCacheEsqueletos.escribir(truncado, HUELLA, cacheEjemplo());
        byte[] completo = Files.readAllBytes(truncado);
        Files.write(truncado, java.util.Arrays.copyOf(completo, completo.length - 6));
        assertTrue(AlmacenCacheEsqueletos.leerSiCoincide(truncado, HUELLA).isEmpty(),
                "un archivo cortado a mitad de escritura degrada a caché vacía, nunca lanza");

        Path inexistente = dir.resolve("no-existe.bin");
        assertTrue(AlmacenCacheEsqueletos.leerSiCoincide(inexistente, HUELLA).isEmpty());
    }

    @Test
    void guardarSiCrecioSoloEscribeCuandoLaCacheGanaClaves(@TempDir Path dir) {
        Path archivo = dir.resolve("skeleton-cache.bin");
        MotorGrafoCache motorCache = new MotorGrafoCache();
        // dataLoader null ⇒ huella constante (dataset vacío); suficiente para la semántica de guardado.
        AlmacenCacheEsqueletos store = new AlmacenCacheEsqueletos(null, motorCache, archivo.toString());

        motorCache.skeletonCache().put(1L, List.of(new int[]{0, 1}));
        store.guardarSiCrecio();
        assertTrue(Files.isRegularFile(archivo), "primer guardado con la caché no vacía");

        // Sin claves nuevas NO reescribe: si borramos el archivo, no reaparece.
        assertTrue(archivo.toFile().delete());
        store.guardarSiCrecio();
        assertFalse(Files.exists(archivo), "sin crecimiento no toca el disco");

        // Con una clave nueva sí vuelve a escribir.
        motorCache.skeletonCache().put(2L, List.of(new int[]{5}));
        store.guardarSiCrecio();
        assertTrue(Files.isRegularFile(archivo));
    }

    @Test
    void borrarEliminaElArchivoYPermiteReescribirDesdeCero(@TempDir Path dir) {
        Path archivo = dir.resolve("skeleton-cache.bin");
        MotorGrafoCache motorCache = new MotorGrafoCache();
        AlmacenCacheEsqueletos store = new AlmacenCacheEsqueletos(null, motorCache, archivo.toString());

        motorCache.skeletonCache().put(1L, List.of(new int[]{0}));
        store.guardarSiCrecio();
        assertTrue(Files.isRegularFile(archivo));

        store.borrar();   // ingesta: dataset nuevo ⇒ archivo fuera
        assertFalse(Files.exists(archivo));

        // Tras borrar (contador a 0), el mismo tamaño de caché vuelve a considerarse crecimiento.
        store.guardarSiCrecio();
        assertTrue(Files.isRegularFile(archivo), "tras la ingesta el próximo pre-warm regenera el archivo");
    }

    @Test
    void cargarAlArranquePueblaLaCacheCompartidaSiLaHuellaCoincide(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("skeleton-cache.bin");
        MotorGrafoCache previa = new MotorGrafoCache();
        AlmacenCacheEsqueletos storePrevia = new AlmacenCacheEsqueletos(null, previa, archivo.toString());
        previa.skeletonCache().put(42L, List.of(new int[]{3, 7}));
        storePrevia.guardarSiCrecio();

        // "Reinicio": MotorGrafoCache nuevo (vacío) + mismo dataset (misma huella: dataLoader null).
        MotorGrafoCache trasReinicio = new MotorGrafoCache();
        AlmacenCacheEsqueletos store = new AlmacenCacheEsqueletos(null, trasReinicio, archivo.toString());
        store.cargarAlArranque();

        assertEquals(1, trasReinicio.skeletonCache().size());
        assertArrayEquals(new int[]{3, 7}, trasReinicio.skeletonCache().get(42L).get(0));

        // Y no re-guarda en falso: lo cargado no cuenta como crecimiento.
        assertTrue(archivo.toFile().delete());
        store.guardarSiCrecio();
        assertFalse(Files.exists(archivo));
    }

    @Test
    void archivoVacioDesactivaLaPersistenciaSinNPE() {
        // Instancia no-op (la usan los constructores de conveniencia de los tests de servicios).
        AlmacenCacheEsqueletos store = new AlmacenCacheEsqueletos(null, null, "");
        store.cargarAlArranque();
        store.guardarSiCrecio();
        store.borrar();   // ninguna debe lanzar
    }
}
