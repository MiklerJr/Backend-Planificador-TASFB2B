package com.tasfb2b.planificador.servicios;

/**
 * Totales acumulados por envío ÚNICO (deduplicados por clave de lote): un envío reprocesado
 * en varios bloques cuenta una sola vez, con su último estado conocido.
 */
record TotalesUnicos(
        int envios, int enrutadas, int sinRuta, int cumpleSLA, int tardadas, long maletas) {
}
