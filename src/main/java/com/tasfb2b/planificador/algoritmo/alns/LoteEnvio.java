package com.tasfb2b.planificador.algoritmo.alns;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.tasfb2b.planificador.algoritmo.grafo.Arista;

public class LoteEnvio {
    private String id;
    private int cantidad;
    private int horasLimiteSla;

    private String codigoOrigen;
    private String codigoDestino;
    private LocalDateTime tiempoListo;
    private Integer clienteId;
    private boolean sintetico;

    private List<Arista> rutaAsignada;
    private List<Long> salidasAsignadas; // epoch-minutes, paralelo a rutaAsignada
    private boolean cumpleSLA;

    private List<Arista> prefijoFijo;
    private List<Long> prefijoFijoSalidas;
    private String origenActual;
    private LocalDateTime tiempoListoActual;

    public LoteEnvio(String id, int cantidad, int horasLimiteSla,
                        String codigoOrigen, String codigoDestino, LocalDateTime tiempoListo) {
        this.id             = id;
        this.cantidad       = cantidad;
        this.horasLimiteSla = horasLimiteSla;
        this.codigoOrigen   = codigoOrigen;
        this.codigoDestino  = codigoDestino;
        this.tiempoListo    = tiempoListo;
        this.rutaAsignada   = new ArrayList<>();
        this.cumpleSLA      = false;
        this.origenActual      = codigoOrigen;
        this.tiempoListoActual = tiempoListo;
    }

    public void setRutaAsignada(List<Arista> route) {
        this.rutaAsignada = route;
    }

    public void setSalidasAsignadas(List<Long> deps) {
        this.salidasAsignadas = deps;
    }

    public void limpiarRuta() {
        this.rutaAsignada = new ArrayList<>();
        this.salidasAsignadas = null;
    }

    public double getTiempoTransitoTotalMin() {
        if (rutaAsignada == null || rutaAsignada.isEmpty()) return 10000.0;

        if (salidasAsignadas != null && !salidasAsignadas.isEmpty()) {
            long readyMin = aMinutoEpoch(tiempoListo);
            int  lastIdx  = rutaAsignada.size() - 1;
            long arrLast  = salidasAsignadas.get(lastIdx)
                          + rutaAsignada.get(lastIdx).duracionMinutos;
            return arrLast - readyMin;
        }
        return java.time.Duration.between(tiempoListo,
                rutaAsignada.get(rutaAsignada.size() - 1).horaLlegada).toMinutes();
    }

    public double getRatioHolguraSla() {
        if (rutaAsignada == null || rutaAsignada.isEmpty()) return -1.0;
        double slaMin = horasLimiteSla * 60.0;
        double transitMin = getTiempoTransitoTotalMin();
        return (slaMin - transitMin) / slaMin;
    }

    // ── Prefijo fijo / posición actual ────────────────────────────────
    public boolean tienePrefijo() {
        return prefijoFijo != null && !prefijoFijo.isEmpty();
    }

    public List<Arista> getRutaCompleta() {
        if (!tienePrefijo()) return rutaAsignada;
        List<Arista> full = new ArrayList<>(prefijoFijo.size()
                + (rutaAsignada != null ? rutaAsignada.size() : 0));
        full.addAll(prefijoFijo);
        if (rutaAsignada != null) full.addAll(rutaAsignada);
        return full;
    }

    public List<Long> getSalidasCompletas() {
        if (!tienePrefijo()) return salidasAsignadas;
        List<Long> full = new ArrayList<>(prefijoFijoSalidas.size()
                + (salidasAsignadas != null ? salidasAsignadas.size() : 0));
        full.addAll(prefijoFijoSalidas);
        if (salidasAsignadas != null) full.addAll(salidasAsignadas);
        return full;
    }

    public String origenEfectivo() {
        return tienePrefijo() ? origenActual : codigoOrigen;
    }

    public LocalDateTime tiempoListoEfectivo() {
        return tienePrefijo() ? tiempoListoActual : tiempoListo;
    }

    public List<Arista> getPrefijoFijo()                 { return prefijoFijo; }
    public void setPrefijoFijo(List<Arista> p)           { this.prefijoFijo = p; }
    public List<Long> getPrefijoFijoSalidas()       { return prefijoFijoSalidas; }
    public void setPrefijoFijoSalidas(List<Long> d) { this.prefijoFijoSalidas = d; }
    public String getOrigenActual()               { return origenActual; }
    public void setOrigenActual(String c)         { this.origenActual = c; }
    public LocalDateTime getTiempoListoActual()         { return tiempoListoActual; }
    public void setTiempoListoActual(LocalDateTime t)   { this.tiempoListoActual = t; }

    public LoteEnvio clonar() {
        LoteEnvio clone = new LoteEnvio(id, cantidad, horasLimiteSla,
                                               codigoOrigen, codigoDestino, tiempoListo);
        clone.setClienteId(this.clienteId);
        clone.setSintetico(this.sintetico);
        clone.setRutaAsignada(new ArrayList<>(this.rutaAsignada));
        clone.setSalidasAsignadas(
                salidasAsignadas != null ? new ArrayList<>(salidasAsignadas) : null);
        clone.setCumpleSLA(this.cumpleSLA);
        // Preservar la posición actual y el prefijo (el ACO no clona, pero por robustez).
        clone.setOrigenActual(this.origenActual);
        clone.setTiempoListoActual(this.tiempoListoActual);
        clone.setPrefijoFijo(this.prefijoFijo != null ? new ArrayList<>(this.prefijoFijo) : null);
        clone.setPrefijoFijoSalidas(
                this.prefijoFijoSalidas != null ? new ArrayList<>(this.prefijoFijoSalidas) : null);
        return clone;
    }

    private static long aMinutoEpoch(LocalDateTime dt) {
        return dt.toLocalDate().toEpochDay() * 1440L + dt.getHour() * 60L + dt.getMinute();
    }

    public String getId()                     { return id; }
    public int getCantidad()                  { return cantidad; }
    public int getHorasLimiteSla()             { return horasLimiteSla; }
    public String getCodigoOrigen()             { return codigoOrigen; }
    public String getCodigoDestino()             { return codigoDestino; }
    public LocalDateTime getTiempoListo()       { return tiempoListo; }
    public List<Arista> getRutaAsignada()      { return rutaAsignada; }
    public List<Long> getSalidasAsignadas() { return salidasAsignadas; }
    public boolean isCumpleSLA()              { return cumpleSLA; }
    public void setCumpleSLA(boolean v)       { this.cumpleSLA = v; }
    public Integer getClienteId()             { return clienteId; }
    public void setClienteId(Integer id)      { this.clienteId = id; }
    public boolean isSintetico()              { return sintetico; }
    public void setSintetico(boolean v)       { this.sintetico = v; }
}
