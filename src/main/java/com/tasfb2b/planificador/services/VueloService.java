package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.VueloRequest;
import com.tasfb2b.planificador.dto.VueloResponse;
import com.tasfb2b.planificador.model.Vuelo;
import com.tasfb2b.planificador.repository.VueloRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VueloService {

    private final VueloRepository vueloRepository;

    public VueloService(VueloRepository vueloRepository) {
        this.vueloRepository = vueloRepository;
    }

    public List<VueloResponse> listar() {
        return vueloRepository.findAll().stream().map(this::toResponse).toList();
    }

    public VueloResponse obtener(String id) {
        return vueloRepository.findById(id).map(this::toResponse).orElse(null);
    }

    public VueloResponse crear(VueloRequest request) {
        Vuelo vuelo = toEntity(request);
        return toResponse(vueloRepository.save(vuelo));
    }

    public VueloResponse actualizar(String id, VueloRequest request) {
        if (!vueloRepository.existsById(id)) return null;
        Vuelo vuelo = toEntity(request);
        vuelo.setId(id);
        return toResponse(vueloRepository.save(vuelo));
    }

    public boolean eliminar(String id) {
        if (!vueloRepository.existsById(id)) return false;
        vueloRepository.deleteById(id);
        return true;
    }

    private Vuelo toEntity(VueloRequest r) {
        Vuelo v = new Vuelo();
        v.setId(r.getId());
        v.setCapacidad(r.getCapacidad());
        v.setOrigen(r.getOrigen());
        v.setDestino(r.getDestino());
        v.setFechaHoraSalida(r.getFechaHoraSalida());
        v.setFechaHoraLlegada(r.getFechaHoraLlegada());
        return v;
    }

    private VueloResponse toResponse(Vuelo v) {
        VueloResponse r = new VueloResponse();
        r.setId(v.getId());
        r.setCapacidad(v.getCapacidad());
        r.setOrigen(v.getOrigen());
        r.setDestino(v.getDestino());
        r.setFechaHoraSalida(v.getFechaHoraSalida());
        r.setFechaHoraLlegada(v.getFechaHoraLlegada());
        return r;
    }
}
