package br.com.evandro.tripper;

import android.content.Context;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Diário de viagem: grava sozinho (começa acima de 10 km/h, termina após 5 min parado).
 * Cada viagem vira files/viagens/AAAA-MM-DD-HHMM.json com pontos [lat, lon, alt, km/h, t].
 */
final class TripRecorder {
    private static final float START_KMH = 10;
    private static final long STOP_AFTER_MS = 5 * 60_000;
    private static final double MIN_TRIP_M = 500;

    static final class Trip {
        final File file;
        final String title;
        final double km;
        final long movingSec;

        Trip(File file, String title, double km, long movingSec) {
            this.file = file;
            this.title = title;
            this.km = km;
            this.movingSec = movingSec;
        }
    }

    private final File dir;
    private final List<double[]> points = new ArrayList<>();
    private boolean recording;
    private long startedAt;
    private long lastMoveAt;
    private long lastSaveAt;
    private double meters;
    private long movingMs;
    private long lastPointAt;

    TripRecorder(Context context) {
        dir = new File(context.getFilesDir(), "viagens");
        dir.mkdirs();
    }

    /** Começa a registrar (só pelo botão "Registrar viagem"). */
    synchronized void start() {
        if (recording) return;
        recording = true;
        startedAt = System.currentTimeMillis();
        points.clear();
        meters = 0;
        movingMs = 0;
        lastMoveAt = 0;
        lastPointAt = 0;
        Logger.line("Diário: registrando viagem");
    }

    synchronized void onLocation(Location l) {
        long now = System.currentTimeMillis();
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        if (!recording) return;
        if (kmh > 3) {
            if (lastMoveAt > 0) movingMs += Math.min(now - lastPointAt, 10_000);
            lastMoveAt = now;
        }
        double[] last = points.isEmpty() ? null : points.get(points.size() - 1);
        double d = last == null ? 0 : RouteManager.distance(last[0], last[1], l.getLatitude(), l.getLongitude());
        if (last == null || d >= 15 || now - lastPointAt >= 5000) {
            points.add(new double[]{l.getLatitude(), l.getLongitude(), l.hasAltitude() ? l.getAltitude() : Double.NaN, kmh, now});
            meters += d;
            lastPointAt = now;
        }
        if (now - lastSaveAt > 60_000) save(false);
    }

    /** Chamado periodicamente: encerra a viagem depois de 5 min parado. */
    synchronized void tick() {
        // Registro manual: só termina pelo botão "Encerrar viagem".
    }

    synchronized double metersSoFar() {
        return meters;
    }

    synchronized void finish() {
        if (!recording) return;
        recording = false;
        if (meters < MIN_TRIP_M) {
            Logger.line("Diário: viagem curta demais (" + Math.round(meters) + " m), descartada");
            new File(dir, name() + ".json").delete();
            return;
        }
        save(true);
        Logger.line(String.format(Locale.ROOT, "Diário: viagem salva, %.1f km", meters / 1000));
    }

    private String name() {
        return new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new Date(startedAt));
    }

    private void save(boolean done) {
        lastSaveAt = System.currentTimeMillis();
        try {
            JSONArray pts = new JSONArray();
            for (double[] p : points) {
                pts.put(new JSONArray().put(round(p[0], 6)).put(round(p[1], 6))
                        .put(Double.isNaN(p[2]) ? JSONObject.NULL : round(p[2], 1)).put(round(p[3], 1)).put((long) p[4]));
            }
            JSONObject o = new JSONObject();
            o.put("inicio", startedAt);
            o.put("metros", Math.round(meters));
            o.put("movimento_s", movingMs / 1000);
            o.put("terminada", done);
            o.put("pontos", pts);
            try (FileOutputStream out = new FileOutputStream(new File(dir, name() + ".json"))) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Logger.line("Diário: falha ao salvar " + e);
        }
    }

    private static double round(double v, int digits) {
        double k = Math.pow(10, digits);
        return Math.round(v * k) / k;
    }

    synchronized boolean isRecording() {
        return recording;
    }

    /** Viagens salvas, mais recente primeiro. */
    List<Trip> list() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        List<Trip> out = new ArrayList<>();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (File f : files) {
            try {
                JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                String when = new SimpleDateFormat("dd/MM HH:mm", new Locale("pt", "BR")).format(new Date(o.getLong("inicio")));
                out.add(new Trip(f, when, o.getLong("metros") / 1000.0, o.optLong("movimento_s")));
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
