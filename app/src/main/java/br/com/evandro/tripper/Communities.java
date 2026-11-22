package br.com.evandro.tripper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Limites oficiais das comunidades do Rio (Prefeitura, Data.Rio, Limite Favelas 2022),
 * compilados em assets/comunidades.json por ferramentas/compilar_comunidades.py.
 */
final class Communities {
    /** Abaixo disso (m²) a área é desenhada, mas não ganha nome no mapa. */
    static final double LABEL_MIN_M2 = 15_000;

    /** Ajuste "Áreas de risco" (pref "risco", ligado por padrão): desligado, nada é desenhado. */
    static volatile boolean shown = true;

    static void loadSetting(Context c) {
        shown = c.getSharedPreferences("painel", Context.MODE_PRIVATE).getBoolean("risco", true);
    }

    static final class Area {
        final String name;
        final double[][] rings; // cada anel: lon,lat,lon,lat...
        final double minLat, maxLat, minLon, maxLon;
        final double centerLat, centerLon;
        final double areaM2;

        Area(String name, double[][] rings) {
            this.name = name;
            this.rings = rings;
            double a = Double.MAX_VALUE, b = -Double.MAX_VALUE, c = Double.MAX_VALUE, d = -Double.MAX_VALUE;
            double sx = 0, sy = 0;
            int n = 0;
            double area = 0;
            for (double[] r : rings) {
                for (int i = 0; i < r.length; i += 2) {
                    c = Math.min(c, r[i]);
                    d = Math.max(d, r[i]);
                    a = Math.min(a, r[i + 1]);
                    b = Math.max(b, r[i + 1]);
                    sx += r[i];
                    sy += r[i + 1];
                    n++;
                }
                area += ringAreaM2(r);
            }
            minLat = a;
            maxLat = b;
            minLon = c;
            maxLon = d;
            centerLon = sx / n;
            centerLat = sy / n;
            areaM2 = area;
        }

        boolean contains(double lat, double lon) {
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false;
            for (double[] r : rings) if (inRing(r, lat, lon)) return true;
            return false;
        }

        private static boolean inRing(double[] r, double lat, double lon) {
            boolean in = false;
            int n = r.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = r[2 * i], yi = r[2 * i + 1], xj = r[2 * j], yj = r[2 * j + 1];
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
            }
            return in;
        }

        private static double ringAreaM2(double[] r) {
            double k = Math.cos(Math.toRadians(r[1])) * 111_320, s = 0;
            int n = r.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                s += (r[2 * j] * k) * (r[2 * i + 1] * 110_540) - (r[2 * i] * k) * (r[2 * j + 1] * 110_540);
            }
            return Math.abs(s) / 2;
        }
    }

    private volatile List<Area> areas = Collections.emptyList();

    void load(Context context) {
        new Thread(() -> {
            try (InputStream in = context.getAssets().open("comunidades.json")) {
                JSONArray arr = new JSONObject(new String(in.readAllBytes(), "UTF-8")).getJSONArray("areas");
                List<Area> list = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    JSONArray p = o.getJSONArray("p");
                    double[][] rings = new double[p.length()][];
                    for (int k = 0; k < p.length(); k++) {
                        JSONArray ring = p.getJSONArray(k);
                        double[] r = new double[ring.length()];
                        for (int m = 0; m < r.length; m++) r[m] = ring.getDouble(m);
                        rings[k] = r;
                    }
                    list.add(new Area(o.optString("nome", ""), rings));
                }
                areas = list;
                Logger.line("Comunidades: " + list.size() + " áreas carregadas");
            } catch (Exception e) {
                Logger.line("Comunidades: falha ao carregar " + e);
            }
        }, "comunidades").start();
    }

    /** Áreas cuja caixa cruza o quadrado de lado 2*deg em volta do ponto. */
    List<Area> near(double lat, double lon, double deg) {
        List<Area> out = new ArrayList<>();
        for (Area a : areas) {
            if (a.maxLat < lat - deg || a.minLat > lat + deg || a.maxLon < lon - deg || a.minLon > lon + deg) continue;
            out.add(a);
        }
        return out;
    }

    Area at(double lat, double lon) {
        for (Area a : near(lat, lon, 0.01)) if (a.contains(lat, lon)) return a;
        return null;
    }
}
