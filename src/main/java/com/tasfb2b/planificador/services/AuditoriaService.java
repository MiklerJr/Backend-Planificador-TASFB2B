package com.tasfb2b.planificador.services;

import com.tasfb2b.planificador.algorithm.aco.ConstantesOperativas;
import com.tasfb2b.planificador.algorithm.grafo.Edge;
import com.tasfb2b.planificador.algorithm.alns.LuggageBatch;
import com.tasfb2b.planificador.dto.auditoria.AuditoriaEnvio;
import com.tasfb2b.planificador.dto.vuelos.VueloCancelado;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


@Service
public class AuditoriaService {

    private static final int TIEMPO_MIN_ESCALA = ConstantesOperativas.TIEMPO_MIN_ESCALA;
    private static final long DEST_STORAGE_MIN = 10L;
    private static final String CSV_HEADER =
            "idEnvio,origen,destino,clienteId,cantidad,tipoEnvio,registroHHMM,deadlineMin,exitoso,motivoFalla,"
                    + "ruta,numTramos,numEscalas,tiempoVueloMin,tiempoEsperaMin,tiempoTotalMin,llegadaMin,"
                    + "slackSlaMin,slackSlaHoras,cumpleSLA,sinCiclos,escalaMinOK,scoreCalidad,"
                    + "fechaHoraInicio,fechaHoraFin\n";

    public static final int FILAS_POR_ARCHIVO = 50_000;

    private static final DateTimeFormatter FMT_NOMBRE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    public AuditoriaEnvio construir(LuggageBatch batch) {
        AuditoriaEnvio audit = new AuditoriaEnvio();
        audit.setIdEnvio(batch.getId());
        audit.setOrigen(batch.getOriginCode());
        audit.setDestino(batch.getDestCode());
        audit.setClienteId(batch.getClienteId());
        audit.setCantidad(batch.getQuantity());
        audit.setTipoEnvio(batch.getSlaLimitHours() <= 24 ? "INTRACONTINENTAL" : "INTERCONTINENTAL");
        audit.setRegistroHHMM(String.format("%02d:%02d",
                batch.getReadyTime().getHour(), batch.getReadyTime().getMinute()));
        audit.setFechaHoraInicio(batch.getReadyTime());

        long readyMin = toEpochMin(batch.getReadyTime());
        int slaMin = batch.getSlaLimitHours() * 60;
        audit.setDeadlineMin(slaMin);

        List<Edge> ruta = batch.getRutaCompleta();   // ruta real = prefijo volado + sufijo
        boolean enrutada = ruta != null && !ruta.isEmpty();

        if (!enrutada) {
            audit.setExitoso(false);
            audit.setMotivoFalla("No se encontró ruta válida");
            audit.setRuta("");
            audit.setSlackSlaMin(slaMin);
            audit.setSlackSlaHoras(slaMin / 60.0);
            return audit;
        }

        StringBuilder rutaStr = new StringBuilder(ruta.get(0).from.code);
        for (Edge e : ruta) rutaStr.append("->").append(e.to.code);
        audit.setRuta(rutaStr.toString());

        int numTramos = ruta.size();
        int numEscalas = Math.max(0, numTramos - 1);
        audit.setNumTramos(numTramos);
        audit.setNumEscalas(numEscalas);

        int tiempoVueloMin = 0;
        for (Edge e : ruta) tiempoVueloMin += e.durationMinutes;

        int tiempoEsperaMin = 0;
        List<Long> deps = batch.getDeparturesCompletas();
        if (deps != null && deps.size() == ruta.size()) {
            for (int i = 0; i < ruta.size() - 1; i++) {
                long llegada = deps.get(i) + ruta.get(i).durationMinutes;
                long salida  = deps.get(i + 1);
                tiempoEsperaMin += (int) Math.max(0, salida - llegada);
            }
        }
        int tiempoTotalMin = tiempoVueloMin + tiempoEsperaMin;
        audit.setTiempoVueloMin(tiempoVueloMin);
        audit.setTiempoEsperaMin(tiempoEsperaMin);
        audit.setTiempoTotalMin(tiempoTotalMin);

        long llegadaEpoch = (deps != null && !deps.isEmpty())
                ? deps.get(deps.size() - 1) + ruta.get(ruta.size() - 1).durationMinutes
                : readyMin + tiempoTotalMin;
        int llegadaDesdeReady = (int) (llegadaEpoch - readyMin);
        audit.setLlegadaMin(llegadaDesdeReady);

        audit.setFechaHoraFin(epochMinToLocalDateTime(llegadaEpoch + DEST_STORAGE_MIN));

        int slack = slaMin - llegadaDesdeReady;
        audit.setSlackSlaMin(slack);
        audit.setSlackSlaHoras(slack / 60.0);

        boolean cumpleSLA  = batch.isCumpleSLA() && slack >= 0;
        boolean sinCiclos  = sinCiclos(ruta);
        boolean escalaOK   = cumpleEscalaMinima(ruta, deps);

        audit.setCumpleSLA(cumpleSLA);
        audit.setSinCiclos(sinCiclos);
        audit.setEscalaMinOK(escalaOK);

        boolean exitoso = cumpleSLA && sinCiclos && escalaOK;
        audit.setExitoso(exitoso);
        audit.setMotivoFalla(exitoso ? "" : motivoFalla(cumpleSLA, sinCiclos, escalaOK));
        audit.setScoreCalidad(calcularScore(sinCiclos, escalaOK, cumpleSLA,
                numEscalas, tiempoEsperaMin, slack));
        return audit;
    }

    public String aCsv(List<AuditoriaEnvio> filas) {
        StringBuilder sb = new StringBuilder();
        sb.append(CSV_HEADER);
        for (AuditoriaEnvio r : filas) {
            sb.append(lineaCsv(r));
        }
        return sb.toString();
    }

    public int escribirCsv(Collection<LuggageBatch> batches, Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            return escribirCsv(batches, writer);
        }
    }

    public int escribirCsv(Collection<LuggageBatch> batches, Writer writer) throws IOException {
        writer.write(CSV_HEADER);
        int filas = 0;
        if (batches == null) return filas;
        for (LuggageBatch b : batches) {
            if (b == null) continue;
            writer.write(lineaCsv(construir(b)));
            filas++;
        }
        return filas;
    }

    public List<AuditoriaEnvio> construirLote(List<LuggageBatch> batches) {
        List<AuditoriaEnvio> out = new ArrayList<>(batches.size());
        for (LuggageBatch b : batches) out.add(construir(b));
        return out;
    }

    public int escribirZip(Collection<LuggageBatch> batches, Path zipPath,
                           int maxFilas, String jobId,
                           Collection<VueloCancelado> vuelosCancelados) throws IOException {
        int limite = maxFilas > 0 ? maxFilas : FILAS_POR_ARCHIVO;

        List<LuggageBatch> ordenados = new ArrayList<>(batches == null ? 0 : batches.size());
        if (batches != null) {
            for (LuggageBatch b : batches) {
                if (b != null) ordenados.add(b);
            }
        }
        ordenados.sort(Comparator.comparing(LuggageBatch::getReadyTime));

        int totalFilas = 0;
        Set<String> nombresUsados = new LinkedHashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipPath)), StandardCharsets.UTF_8)) {

            if (ordenados.isEmpty()) {
                // CSV de envíos vacío (solo cabecera) para no devolver un archivo corrupto.
                zos.putNextEntry(new ZipEntry(nombreArchivo(jobId, null, null, nombresUsados)));
                Writer w = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
                w.write(CSV_HEADER);
                w.flush();
                zos.closeEntry();
            } else {
                int n = ordenados.size();
                for (int desde = 0; desde < n; desde += limite) {
                    int hasta = Math.min(desde + limite, n);
                    LocalDateTime inicio = ordenados.get(desde).getReadyTime();
                    LocalDateTime fin    = ordenados.get(hasta - 1).getReadyTime();

                    zos.putNextEntry(new ZipEntry(nombreArchivo(jobId, inicio, fin, nombresUsados)));
                    Writer w = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
                    w.write(CSV_HEADER);
                    for (int i = desde; i < hasta; i++) {
                        w.write(lineaCsv(construir(ordenados.get(i))));
                        totalFilas++;
                    }
                    // flush (no close) para no cerrar el ZipOutputStream subyacente.
                    w.flush();
                    zos.closeEntry();
                }
            }

            // CSV de vuelos cancelados (siempre presente, aunque solo lleve la cabecera).
            escribirCsvVuelosCancelados(zos, jobId, vuelosCancelados);
        }
        return totalFilas;
    }

    public int escribirZipStreaming(Path zipPath, int maxFilas, String jobId,
                                    java.util.function.Consumer<java.util.function.Consumer<LuggageBatch>> fuenteEnrutados,
                                    Collection<LuggageBatch> sinRuta,
                                    Collection<VueloCancelado> vuelosCancelados) throws IOException {
        int limite = maxFilas > 0 ? maxFilas : FILAS_POR_ARCHIVO;
        Set<String> nombresUsados = new LinkedHashSet<>();
        int[] total = { 0 };
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipPath)), StandardCharsets.UTF_8)) {

            EscritorParticionado esc = new EscritorParticionado(zos, limite, jobId, nombresUsados);
            java.util.function.Consumer<LuggageBatch> sink = b -> {
                if (b == null) return;
                esc.escribir(construir(b));
                total[0]++;
            };

            if (fuenteEnrutados != null) fuenteEnrutados.accept(sink);
            if (sinRuta != null && !sinRuta.isEmpty()) {
                List<LuggageBatch> orden = new ArrayList<>(sinRuta);
                orden.sort(Comparator.comparing(LuggageBatch::getReadyTime));
                for (LuggageBatch b : orden) sink.accept(b);
            }
            esc.cerrar();   // vuelca lo pendiente; si no hubo filas, deja un CSV solo-cabecera

            // CSV de vuelos cancelados (siempre presente, aunque solo lleve la cabecera).
            escribirCsvVuelosCancelados(zos, jobId, vuelosCancelados);
        }
        return total[0];
    }

    private static final class EscritorParticionado {
        private final ZipOutputStream zos;
        private final int limite;
        private final String jobId;
        private final Set<String> nombresUsados;
        private final List<String> buffer = new ArrayList<>();
        private LocalDateTime min;
        private LocalDateTime max;
        private boolean algoEscrito = false;

        EscritorParticionado(ZipOutputStream zos, int limite, String jobId, Set<String> nombresUsados) {
            this.zos = zos;
            this.limite = limite;
            this.jobId = jobId;
            this.nombresUsados = nombresUsados;
        }

        void escribir(AuditoriaEnvio audit) {
            buffer.add(lineaCsv(audit));
            LocalDateTime ready = audit.getFechaHoraInicio();
            if (ready != null) {
                if (min == null || ready.isBefore(min)) min = ready;
                if (max == null || ready.isAfter(max)) max = ready;
            }
            if (buffer.size() >= limite) volcar();
        }

        private void volcar() {
            if (buffer.isEmpty()) return;
            try {
                zos.putNextEntry(new ZipEntry(nombreArchivo(jobId, min, max, nombresUsados)));
                Writer w = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
                w.write(CSV_HEADER);
                for (String l : buffer) w.write(l);
                w.flush();
                zos.closeEntry();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            buffer.clear();
            min = null;
            max = null;
            algoEscrito = true;
        }

        void cerrar() {
            volcar();
            if (!algoEscrito) {
                // Ningún envío: CSV vacío (solo cabecera) para no devolver un ZIP corrupto.
                try {
                    zos.putNextEntry(new ZipEntry(nombreArchivo(jobId, null, null, nombresUsados)));
                    Writer w = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
                    w.write(CSV_HEADER);
                    w.flush();
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }
    }

    private static final String CSV_HEADER_CANCELADOS =
            "origen,destino,fechaHoraSalida,enviosAfectados\n";

    private void escribirCsvVuelosCancelados(ZipOutputStream zos, String jobId,
                                             Collection<VueloCancelado> vuelosCancelados) throws IOException {
        String pref = (jobId == null || jobId.isBlank()) ? "job" : jobId;
        zos.putNextEntry(new ZipEntry(pref + "-vuelos-cancelados.csv"));
        Writer w = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
        w.write(CSV_HEADER_CANCELADOS);
        if (vuelosCancelados != null) {
            for (VueloCancelado v : vuelosCancelados) {
                if (v == null) continue;
                w.write(csv(v.getOrigen()) + ','
                        + csv(v.getDestino()) + ','
                        + (v.getFechaHoraSalida() == null ? "" : v.getFechaHoraSalida().toString()) + ','
                        + v.getEnviosAfectados() + '\n');
            }
        }
        w.flush();
        zos.closeEntry();
    }

    private static String nombreArchivo(String jobId, LocalDateTime inicio,
                                        LocalDateTime fin, Set<String> usados) {
        String pref = (jobId == null || jobId.isBlank()) ? "job" : jobId;
        String base = (inicio == null || fin == null)
                ? pref + "-vacio"
                : pref + "-" + FMT_NOMBRE.format(inicio) + "-" + FMT_NOMBRE.format(fin);
        String nombre = base + ".csv";
        int sufijo = 1;
        while (!usados.add(nombre)) {
            nombre = base + "-" + (++sufijo) + ".csv";
        }
        return nombre;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean sinCiclos(List<Edge> ruta) {
        Set<String> visitados = new HashSet<>();
        visitados.add(ruta.get(0).from.code);
        for (Edge e : ruta) {
            if (!visitados.add(e.to.code)) return false;
        }
        return true;
    }

    private static boolean cumpleEscalaMinima(List<Edge> ruta, List<Long> deps) {
        if (deps == null || deps.size() != ruta.size()) {
            // Sin info de departures reales, validamos contra los tiempos estáticos.
            for (int i = 0; i < ruta.size() - 1; i++) {
                int salidaSig = ruta.get(i + 1).depMinuteOfDay;
                int llegadaAct = (ruta.get(i).depMinuteOfDay + ruta.get(i).durationMinutes) % 1440;
                int diff = salidaSig - llegadaAct;
                if (diff < 0) diff += 1440;
                if (diff < TIEMPO_MIN_ESCALA) return false;
            }
            return true;
        }
        for (int i = 0; i < ruta.size() - 1; i++) {
            long llegada = deps.get(i) + ruta.get(i).durationMinutes;
            long salida  = deps.get(i + 1);
            if (salida - llegada < TIEMPO_MIN_ESCALA) return false;
        }
        return true;
    }

    private static String motivoFalla(boolean cumpleSLA, boolean sinCiclos,
                                       boolean escalaOK) {
        if (!cumpleSLA)  return "SLA incumplido";
        if (!sinCiclos)  return "Ruta con ciclos";
        if (!escalaOK)   return "Tiempo mínimo de escala violado";
        return "Restricción no identificada";
    }

    private static int calcularScore(boolean sinCiclos, boolean escalaMinOk,
                                      boolean cumpleSLA, int escalas,
                                      int tiempoEsperaMin, int slackSlaMin) {
        if (!sinCiclos || !escalaMinOk || !cumpleSLA) {
            return 0;
        }
        double score = 100.0;
        int excesoEscalas = Math.max(0, escalas - 2);
        score -= excesoEscalas * 15.0;
        score -= tiempoEsperaMin * 0.05;
        if (slackSlaMin < 60) score -= 20.0;
        return (int) Math.max(0, Math.round(score));
    }

    private static String lineaCsv(AuditoriaEnvio r) {
        return csv(r.getIdEnvio()) + ','
                + csv(r.getOrigen()) + ','
                + csv(r.getDestino()) + ','
                + (r.getClienteId() == null ? "" : r.getClienteId()) + ','
                + r.getCantidad() + ','
                + csv(r.getTipoEnvio()) + ','
                + csv(r.getRegistroHHMM()) + ','
                + r.getDeadlineMin() + ','
                + r.isExitoso() + ','
                + csv(r.getMotivoFalla()) + ','
                + csv(r.getRuta()) + ','
                + r.getNumTramos() + ','
                + r.getNumEscalas() + ','
                + r.getTiempoVueloMin() + ','
                + r.getTiempoEsperaMin() + ','
                + r.getTiempoTotalMin() + ','
                + r.getLlegadaMin() + ','
                + r.getSlackSlaMin() + ','
                + String.format(java.util.Locale.US, "%.2f", r.getSlackSlaHoras()) + ','
                + r.isCumpleSLA() + ','
                + r.isSinCiclos() + ','
                + r.isEscalaMinOK() + ','
                + r.getScoreCalidad() + ','
                + formatoFecha(r.getFechaHoraInicio()) + ','
                + formatoFecha(r.getFechaHoraFin()) + '\n';
    }

    private static String csv(String texto) {
        if (texto == null) return "";
        if (texto.contains(",") || texto.contains("\"") || texto.contains("\n")) {
            return "\"" + texto.replace("\"", "\"\"") + "\"";
        }
        return texto;
    }

    private static long toEpochMin(java.time.LocalDateTime dt) {
        return dt.toLocalDate().toEpochDay() * 1440L + dt.getHour() * 60L + dt.getMinute();
    }

    private static LocalDateTime epochMinToLocalDateTime(long epochMin) {
        long epochDay = Math.floorDiv(epochMin, 1440L);
        long minuteOfDay = Math.floorMod(epochMin, 1440L);
        LocalDate date = LocalDate.ofEpochDay(epochDay);
        LocalTime time = LocalTime.of((int) (minuteOfDay / 60), (int) (minuteOfDay % 60));
        return LocalDateTime.of(date, time);
    }

    private static String formatoFecha(LocalDateTime dt) {
        return dt == null ? "" : dt.toString();
    }
}
