package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.dto.AeropuertoRequest;
import com.tasfb2b.planificador.dto.AeropuertoResponse;
import com.tasfb2b.planificador.model.Aeropuerto;
import com.tasfb2b.planificador.repository.AeropuertoRepository;
import com.tasfb2b.planificador.util.ContinenteUtil;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AeropuertoService {

    private final AeropuertoRepository repository;

    public AeropuertoService(AeropuertoRepository repository) {
        this.repository = repository;
    }

    public List<AeropuertoResponse> listar() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    public AeropuertoResponse obtener(String codigo) {
        return repository.findById(codigo).map(this::toResponse).orElse(null);
    }

    public AeropuertoResponse crear(AeropuertoRequest request) {
        Aeropuerto aeropuerto = toEntity(request);
        return toResponse(repository.save(aeropuerto));
    }

    public AeropuertoResponse actualizar(String codigo, AeropuertoRequest request) {
        if (!repository.existsById(codigo)) return null;
        Aeropuerto aeropuerto = toEntity(request);
        aeropuerto.setCodigo(codigo);
        return toResponse(repository.save(aeropuerto));
    }

    public boolean eliminar(String codigo) {
        if (!repository.existsById(codigo)) return false;
        repository.deleteById(codigo);
        return true;
    }

    private Aeropuerto toEntity(AeropuertoRequest r) {
        Aeropuerto a = new Aeropuerto();
        a.setCodigo(r.getCodigo());
        a.setCiudad(r.getCiudad());
        a.setPais(r.getPais());
        a.setOffsetHorario(r.getOffsetHorario());
        a.setContinente(ContinenteUtil.desdeIcao(r.getCodigo()));
        a.setCapacidad(r.getCapacidad());
        a.setLatitud(r.getLatitud());
        a.setLongitud(r.getLongitud());
        a.setActivo(r.getActivo() == null || r.getActivo());
        return a;
    }

    private AeropuertoResponse toResponse(Aeropuerto a) {
        AeropuertoResponse r = new AeropuertoResponse();
        r.setCodigo(a.getCodigo());
        r.setCiudad(a.getCiudad());
        r.setPais(a.getPais());
        r.setOffsetHorario(a.getOffsetHorario());
        r.setCapacidad(a.getCapacidad());
        r.setLatitud(a.getLatitud());
        r.setLongitud(a.getLongitud());
        r.setActivo(a.isActivo());
        return r;
    }
}
