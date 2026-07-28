package com.tasfb2b.planificador.servicios;

import com.tasfb2b.planificador.modelo.datos.Aeropuerto;
import com.tasfb2b.planificador.utilidades.CargadorDatos;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CacheOffsetsAeropuerto {

    private final CargadorDatos cargadorDatos;

    private volatile Map<String, Integer> offsetPorCodigo = null;

    public CacheOffsetsAeropuerto(CargadorDatos cargadorDatos) {
        this.cargadorDatos = cargadorDatos;
    }

    public int offsetHoras(String codigo) {
        Map<String, Integer> mapa = offsetPorCodigo;
        if (mapa == null) {
            mapa = new HashMap<>();
            List<Aeropuerto> aeropuertos = cargadorDatos != null ? cargadorDatos.getAeropuertos() : List.of();
            for (Aeropuerto a : aeropuertos) {
                if (a.getCodigo() != null && a.getOffsetHorario() != null) {
                    mapa.put(a.getCodigo(), a.getOffsetHorario());
                }
            }
            offsetPorCodigo = mapa;
        }
        return mapa.getOrDefault(codigo, 0);
    }
}
