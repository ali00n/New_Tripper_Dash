package br.com.evandro.tripper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;

/**
 * Radares (OpenStreetMap, compilados em assets/radares.json).
 * À frente e a menos de 400 m: brilho nas bordas (amarelo; vermelho pulsando acima do limite)
 * e placa de velocidade no canto.
 */
final class SpeedCameras {
    private static final double RANGE_M = 400;
    /** Aviso por tempo: ~12 s antes do radar na velocidade atual, entre 150 e 400 m. */
    private static final double WARN_SECONDS = 12;
    private static final double MIN_RANGE_M = 150;
    private static final double AHEAD_DEG = 40;
    private static final int TOLERANCE_KMH = 3;

    private volatile double[] lat = new double[0];
    private volatile double[] lon = new double[0];
    private volatile int[] limit = new int[0];
    private volatile int[] dir = new int[0];
    /** Direção da rua no ponto do radar (0-180), -1 = desconhecida. */
    private volatile int[] axis = new int[0];
    /** Radares marcados pelo Evandro como "não existe" (lat,lon com 5 casas). */
    private final java.util.Set<String> ignored = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private android.content.SharedPreferences prefs;
    private volatile int lastSeen = -1;
    private volatile long lastSeenAt;
    /** Margem para o "ao longo da rua" quando não se sabe o sentido do radar. */
    private static final double AXIS_DEG = 35;

    private int nearest = -1;
    private double nearestDist;
    private final Paint glow = new Paint();
    private final Paint signFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint distText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint distHalo = new Paint(Paint.ANTI_ALIAS_FLAG);

    SpeedCameras() {
        signFill.setColor(Color.WHITE);
        signRing.setColor(Color.rgb(215, 25, 25));
        signRing.setStyle(Paint.Style.STROKE);
        signRing.setStrokeWidth(7);
        signText.setColor(Color.BLACK);
        signText.setTextSize(26);
        signText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        signText.setTextAlign(Paint.Align.CENTER);
        distText.setColor(Color.WHITE);
        distText.setTextSize(17);
        distText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        distText.setTextAlign(Paint.Align.CENTER);
        distHalo.set(distText);
        distHalo.setColor(Color.BLACK);
        distHalo.setStyle(Paint.Style.STROKE);
        distHalo.setStrokeWidth(4);
    }

    void load(Context context) {
        prefs = context.getSharedPreferences("radares", Context.MODE_PRIVATE);
        ignored.addAll(prefs.getStringSet("ignorados", java.util.Collections.emptySet()));
        new Thread(() -> {
            try (InputStream in = context.getAssets().open("radares.json")) {
                JSONArray r = new JSONObject(new String(in.readAllBytes(), "UTF-8")).getJSONArray("r");
                int n = r.length();
                double[] la = new double[n], lo = new double[n];
                int[] li = new int[n], di = new int[n], ax = new int[n];
                for (int i = 0; i < n; i++) {
                    JSONArray c = r.getJSONArray(i);
                    la[i] = c.getDouble(0);
                    lo[i] = c.getDouble(1);
                    li[i] = c.getInt(2);
                    di[i] = c.getInt(3);
                    ax[i] = c.length() > 4 ? c.getInt(4) : -1;
                }
                axis = ax;
                lat = la;
                lon = lo;
                limit = li;
                dir = di;
                Logger.line("Radares: " + n + " carregados, " + ignored.size() + " marcados como inexistentes");
            } catch (Exception e) {
                Logger.line("Radares: falha ao carregar " + e);
            }
        }, "radares").start();
    }

    /** Escolhe o radar mais próximo à frente da moto. */
    void update(double myLat, double myLon, float heading, double speedMs) {
        double[] la = lat, lo = lon;
        int[] di = dir, ax = axis;
        int best = -1;
        // já avisando: mantém o alcance cheio (reduzir a velocidade não apaga o aviso)
        double bestD = nearest >= 0 ? RANGE_M : Math.max(MIN_RANGE_M, Math.min(RANGE_M, speedMs * WARN_SECONDS));
        double k = Math.cos(Math.toRadians(myLat)) * 111_320;
        for (int i = 0; i < la.length; i++) {
            double dy = (la[i] - myLat) * 110_540;
            if (Math.abs(dy) > RANGE_M) continue;
            double dx = (lo[i] - myLon) * k;
            if (Math.abs(dx) > RANGE_M) continue;
            double d = Math.hypot(dx, dy);
            if (d >= bestD) continue;
            double brg = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360;
            if (d > 25 && angle(brg, heading) > AHEAD_DEG) continue;
            if (di[i] >= 0 && angle(di[i], heading) > 60) continue;
            // sem sentido conhecido: só vale para quem anda ao longo da rua do radar (nunca para quem cruza)
            if (di[i] < 0 && i < ax.length && ax[i] >= 0 && Math.min(angle(ax[i], heading), angle(ax[i] + 180, heading)) > AXIS_DEG) continue;
            if (!ignored.isEmpty() && ignored.contains(key(la[i], lo[i]))) continue;
            best = i;
            bestD = d;
        }
        if (best >= 0) {
            lastSeen = best;
            lastSeenAt = System.currentTimeMillis();
        }
        if (best != nearest) {
            if (best >= 0) Logger.line("Radar à frente: " + Math.round(bestD) + " m, limite " + (limit[best] > 0 ? limit[best] : "?"));
            nearest = best;
        }
        nearestDist = bestD;
    }

    private static String key(double la, double lo) {
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f", la, lo);
    }

    /** "Não tem radar aqui": marca o radar do último aviso (até 2 min depois de passar). */
    String ignoreRecent() {
        int i = lastSeen;
        if (i < 0 || System.currentTimeMillis() - lastSeenAt > 120_000) return "Nenhum aviso de radar nos últimos 2 minutos.";
        ignored.add(key(lat[i], lon[i]));
        if (prefs != null) prefs.edit().putStringSet("ignorados", new java.util.HashSet<>(ignored)).apply();
        nearest = -1;
        lastSeen = -1;
        Logger.line("Radares: marcado como inexistente " + key(lat[i], lon[i]));
        return "Radar removido. Não vai mais avisar nesse ponto.";
    }

    /** Limite do radar à frente (-1 = nenhum). */
    int aheadLimit() {
        int i = nearest;
        return i < 0 ? -1 : limit[i];
    }

    int aheadDistance() {
        return (int) Math.round(nearestDist);
    }

    private static double angle(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    /** Efeito de borda + placa. speedKmh = velocidade atual. */
    void paint(Canvas c, int w, int h, float speedKmh) {
        int i = nearest;
        if (i < 0) return;
        int lim = limit[i];
        boolean over = lim > 0 && speedKmh > lim + TOLERANCE_KMH;
        float near = (float) Math.max(0.65, 1 - nearestDist / RANGE_M);
        float pulse = over ? (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 80.0)) : 1f;
        int alpha = Math.min(255, (int) (255 * near * pulse));
        int rgb = over ? Color.rgb(255, 0, 0) : Color.rgb(255, 200, 0);
        int edge = (alpha << 24) | (rgb & 0xFFFFFF);
        int mid = ((alpha / 2) << 24) | (rgb & 0xFFFFFF);
        float radius = (float) Math.hypot(w, h) / 2f;
        glow.setShader(new RadialGradient(w / 2f, h / 2f, radius,
                new int[]{0x00000000, 0x00000000, mid, edge}, new float[]{0f, 0.40f, 0.70f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glow);
        glow.setShader(null);
        glow.setColor(edge);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(14);
        c.drawRect(7, 7, w - 7, h - 7, glow);
        glow.setStyle(Paint.Style.FILL);

        float cx = w - 48, cy = h - 62, r = 30;
        c.drawCircle(cx, cy, r, signFill);
        c.drawCircle(cx, cy, r - 3, signRing);
        c.drawText(lim > 0 ? String.valueOf(lim) : "?", cx, cy + 9, signText);
        String d = Math.round(nearestDist / 10.0) * 10 + " m";
        c.drawText(d, cx, cy + r + 20, distHalo);
        c.drawText(d, cx, cy + r + 20, distText);
    }

    /** Para teste pelo acesso remoto: posição do radar mais próximo de um ponto. */
    String nearestTo(double myLat, double myLon) {
        double[] la = lat, lo = lon;
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < la.length; i++) {
            double d = RouteManager.distance(myLat, myLon, la[i], lo[i]);
            if (d < bd) { bd = d; best = i; }
        }
        return best < 0 ? "nenhum" : String.format(java.util.Locale.ROOT, "%.6f,%.6f limite=%d dist=%.0fm", la[best], lo[best], limit[best], bd);
    }
}
