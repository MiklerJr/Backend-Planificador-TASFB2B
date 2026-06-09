/*
package com.tasfb2b.planificador.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ⚠️ DIAGNÓSTICO TEMPORAL — BORRAR TRAS VERIFICAR ⚠️
 *
 * <p>Al arrancar la app imprime en consola la cantidad de envíos por día,
 * divididos en bloques de 12 horas (00:00-12:00 y 12:00-24:00), sobre TODA la
 * tabla {@code ENVIO} de PostgreSQL. Sirve solo para confirmar que la data está
 * bien cargada; no forma parte de la lógica de negocio.
 *
 * <p>Usa una única consulta {@code GROUP BY} (no carga filas a RAM) y recorre el
 * resultado en streaming. Agrupa por {@code fecha_hora_registro} en su valor crudo
 * (hora local del origen), coherente con el "Rango en BD" que muestra
 * {@link DataLoader}. Corre después de los {@code @PostConstruct}, por lo que sale
 * justo después del "RESUMEN DE DATOS".
 *
 * <p>Para eliminarlo basta con borrar esta clase: no hay otras dependencias.
 */

/*
@Slf4j
@Component
public class DiagnosticoEnviosPorBloqueRunner implements CommandLineRunner {

    private static final String SQL = """
            SELECT CAST(fecha_hora_registro AS DATE)                                       AS dia,
                   CASE WHEN EXTRACT(HOUR FROM fecha_hora_registro) < 12 THEN 0 ELSE 1 END AS bloque,
                   COUNT(*)                                                                AS envios
            FROM ENVIO
            GROUP BY dia, bloque
            ORDER BY dia, bloque
            """;

    private final JdbcTemplate jdbcTemplate;

    public DiagnosticoEnviosPorBloqueRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        log.info("===== DIAGNÓSTICO: ENVÍOS POR DÍA / BLOQUE 12h (TEMPORAL) =====");
        AtomicLong total = new AtomicLong(0);
        try {
            jdbcTemplate.query(SQL, rs -> {
                String dia = rs.getString("dia");
                int bloque = rs.getInt("bloque");
                long envios = rs.getLong("envios");
                total.addAndGet(envios);
                String franja = bloque == 0 ? "00:00-12:00" : "12:00-24:00";
                log.info("{} | {} | {} envíos", dia, franja, String.format("%,d", envios));
            });
            log.info("Total envíos contados: {}", String.format("%,d", total.get()));
        } catch (Exception e) {
            log.warn("No se pudo generar el diagnóstico de envíos por bloque. "
                    + "¿Está PostgreSQL apagado o la tabla vacía? {}", e.getMessage());
        }
        log.info("==============================================================");
    }
}

*/