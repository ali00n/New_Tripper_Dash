package br.com.evandro.tripper;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Resumo de um rolê gravado (distância, tempos, velocidades, subida) e exportação em GPX. */
final class TripSummary {
    double km, avgMovingKmh, maxKmh, ascentM, maxAltM;
    long startMs, totalSec, movingSec, stoppedSec;
    boolean hasAlt;
    int points;
    private JSONArray pts;

    static TripSummary load(File f) throws Exception {
        JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        TripSummary s = new TripSummary();
        s.pts = o.getJSONArray("pontos");
        s.points = s.pts.length();
        s.startMs = o.getLong("inicio");
        s.km = o.getLong("metros") / 1000.0;
        s.movingSec = o.optLong("movimento_s");
        if (s.points > 0) {
            long t0 = s.pts.getJSONArray(0).getLong(4), t1 = s.pts.getJSONArray(s.points - 1).getLong(4);
            s.totalSec = Math.max(0, (t1 - t0) / 1000);
        }
        s.movingSec = Math.min(s.movingSec, s.totalSec);
        s.stoppedSec = Math.max(0, s.totalSec - s.movingSec);
        s.avgMovingKmh = s.movingSec > 0 ? s.km / (s.movingSec / 3600.0) : 0;
        double[] alt = new double[s.points];
        int n = 0;
        for (int i = 0; i < s.points; i++) {
            JSONArray p = s.pts.getJSONArray(i);
            double v = p.optDouble(3, 0);
            if (v <= 250) s.maxKmh = Math.max(s.maxKmh, v);
            if (!p.isNull(2)) alt[n++] = p.getDouble(2);
        }
        s.hasAlt = n >= 5;
        if (s.hasAlt) {
            // média móvel de 5 pontos e só conta degraus de 1 m ou mais (o GPS "treme" na altitude)
            double[] sm = new double[n];
            for (int i = 0; i < n; i++) {
                double sum = 0;
                int c = 0;
                for (int k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 2); k++) {
                    sum += alt[k];
                    c++;
                }
                sm[i] = sum / c;
                s.maxAltM = Math.max(s.maxAltM, sm[i]);
            }
            double ref = sm[0];
            for (int i = 1; i < n; i++) {
                if (sm[i] - ref >= 1) {
                    s.ascentM += sm[i] - ref;
                    ref = sm[i];
                } else if (ref - sm[i] >= 1) {
                    ref = sm[i];
                }
            }
        }
        return s;
    }

    private static String dur(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h > 0 ? h + " h " + String.format(Locale.ROOT, "%02d", m) + " min" : m + " min";
    }

    String text() {
        Locale br = new Locale("pt", "BR");
        StringBuilder b = new StringBuilder();
        b.append(String.format(br, "Distância: %.1f km\n", km));
        b.append("Tempo total: ").append(dur(totalSec)).append('\n');
        b.append("Em movimento: ").append(dur(movingSec)).append("  ·  Parado: ").append(dur(stoppedSec)).append('\n');
        b.append(String.format(br, "Velocidade média (rodando): %.0f km/h\n", avgMovingKmh));
        b.append(String.format(br, "Velocidade máxima: %.0f km/h", maxKmh));
        if (hasAlt) b.append(String.format(br, "\nSubida acumulada: %.0f m  ·  Altitude máxima: %.0f m", ascentM, maxAltM));
        return b.toString();
    }

    /** Salva em Downloads/NewTripperDash. Devolve o nome do arquivo, ou null se falhou. */
    String exportGpx(Context ctx, String baseName) {
        try {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            StringBuilder g = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    .append("<gpx version=\"1.1\" creator=\"New Tripper Dash\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                    .append("<trk><name>Role ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(new Date(startMs))).append("</name><trkseg>\n");
            for (int i = 0; i < points; i++) {
                JSONArray p = pts.getJSONArray(i);
                g.append("<trkpt lat=\"").append(p.getDouble(0)).append("\" lon=\"").append(p.getDouble(1)).append("\">");
                if (!p.isNull(2)) g.append("<ele>").append(p.getDouble(2)).append("</ele>");
                g.append("<time>").append(iso.format(new Date(p.getLong(4)))).append("</time></trkpt>\n");
            }
            g.append("</trkseg></trk></gpx>\n");
            String name = baseName + ".gpx";
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NewTripperDash");
            Uri u = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            try (OutputStream out = ctx.getContentResolver().openOutputStream(u)) {
                out.write(g.toString().getBytes(StandardCharsets.UTF_8));
            }
            return name;
        } catch (Exception e) {
            Logger.line("GPX: falhou " + e);
            return null;
        }
    }
}
