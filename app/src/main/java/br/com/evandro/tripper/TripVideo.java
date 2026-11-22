package br.com.evandro.tripper;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Animação da viagem no estilo Strava, vertical 1080x1920 (Reels/Stories):
 * traçado se desenhando no mapa com a vaca, relevo com cursor e números contando.
 */
final class TripVideo {
    static final int W = 1080, H = 1920;
    private static final int FPS = 30;
    // Linha do tempo (s): visão geral → mergulho → voo 3D seguindo a vaca → sobe → resumo.
    private static final float T_OVERVIEW = 1.2f, T_DIVE = 2.0f, T_FLY_END = 8.5f, T_RISE = 9.3f, T_TOTAL = 11.5f;
    private static final float FLY_SCALE = 1.7f;
    private static final float FOCUS_X = 540, FOCUS_Y = 1240;
    private static final int MAP_TOP = 250, MAP_SIZE = 1080;
    private static final int TILE = 256;

    private final MapPainter net;
    private final Context context;

    // dados da viagem
    private double[] lat, lon, ele, dist, kmh;
    private long startMs;
    private double totalM, maxKmh, avgKmh, gain;
    private long movingSec;
    private Bitmap base;
    private int zoom;
    private double originX, originY; // canto superior esquerdo da área do mapa, em pixels de mundo
    private double minEle, maxEle;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    TripVideo(Context context, MapPainter net) {
        this.context = context.getApplicationContext();
        this.net = net;
    }

    /** Carrega a viagem, busca relevo real (SRTM) e monta o mapa de fundo. */
    void prepare(File tripFile) throws Exception {
        JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(tripFile.toPath()), StandardCharsets.UTF_8));
        JSONArray pts = o.getJSONArray("pontos");
        int n = pts.length();
        if (n < 2) throw new IllegalStateException("viagem sem pontos");
        lat = new double[n];
        lon = new double[n];
        ele = new double[n];
        kmh = new double[n];
        dist = new double[n];
        for (int i = 0; i < n; i++) {
            JSONArray a = pts.getJSONArray(i);
            lat[i] = a.getDouble(0);
            lon[i] = a.getDouble(1);
            ele[i] = a.isNull(2) ? Double.NaN : a.getDouble(2);
            kmh[i] = a.optDouble(3, 0);
            if (i > 0) dist[i] = dist[i - 1] + RouteManager.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }
        startMs = o.getLong("inicio");
        totalM = dist[n - 1];
        movingSec = o.optLong("movimento_s", 0);
        for (double v : kmh) maxKmh = Math.max(maxKmh, v);
        avgKmh = movingSec > 0 ? totalM / movingSec * 3.6 : 0;
        fetchElevation();
        smoothAndGain();
        buildBase();
        loadFlyTiles();
    }

    /** Relevo do OpenTopoData (SRTM 90 m, grátis, até 100 pontos por pedido); se falhar, fica a altitude do GPS. */
    private void fetchElevation() {
        int n = lat.length;
        int samples = Math.min(100, n);
        double[] sampled = new double[samples];
        int[] idx = new int[samples];
        StringBuilder locs = new StringBuilder();
        for (int s = 0; s < samples; s++) {
            idx[s] = (int) Math.round((double) s * (n - 1) / Math.max(1, samples - 1));
            if (s > 0) locs.append('|');
            locs.append(String.format(Locale.ROOT, "%.5f,%.5f", lat[idx[s]], lon[idx[s]]));
        }
        try {
            JSONArray res = new JSONObject(new String(net.fetch("https://api.opentopodata.org/v1/srtm90m?locations=" + locs), "UTF-8"))
                    .getJSONArray("results");
            for (int s = 0; s < samples; s++) sampled[s] = res.getJSONObject(s).optDouble("elevation", Double.NaN);
            for (int s = 0; s + 1 < samples; s++) {
                for (int i = idx[s]; i <= idx[s + 1]; i++) {
                    double t = idx[s + 1] == idx[s] ? 0 : (double) (i - idx[s]) / (idx[s + 1] - idx[s]);
                    ele[i] = sampled[s] + (sampled[s + 1] - sampled[s]) * t;
                }
            }
            Logger.line("Diário: relevo SRTM com " + samples + " pontos");
        } catch (Exception e) {
            Logger.line("Diário: sem relevo SRTM (" + e.getMessage() + "), usando altitude do GPS");
        }
    }

    private void smoothAndGain() {
        int n = ele.length;
        double[] s = new double[n];
        double last = Double.NaN;
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int c = 0;
            for (int k = Math.max(0, i - 3); k <= Math.min(n - 1, i + 3); k++) {
                if (!Double.isNaN(ele[k])) {
                    sum += ele[k];
                    c++;
                }
            }
            s[i] = c > 0 ? sum / c : (Double.isNaN(last) ? 0 : last);
            last = s[i];
        }
        ele = s;
        minEle = Double.MAX_VALUE;
        maxEle = -Double.MAX_VALUE;
        double ref = ele[0];
        for (double v : ele) {
            minEle = Math.min(minEle, v);
            maxEle = Math.max(maxEle, v);
            if (v - ref > 2) {
                gain += v - ref;
                ref = v;
            } else if (v < ref) {
                ref = v;
            }
        }
        if (maxEle - minEle < 20) maxEle = minEle + 20;
    }

    private static double wx(double lon, int z) {
        return (lon + 180.0) / 360.0 * TILE * Math.pow(2, z);
    }

    private static double wy(double lat, int z) {
        double s = Math.sin(Math.toRadians(lat));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TILE * Math.pow(2, z);
    }

    /** Escolhe o zoom que cabe a viagem em 1080x1080 e desenha os tiles (estilo noturno). */
    private void buildBase() {
        double minLa = Double.MAX_VALUE, maxLa = -Double.MAX_VALUE, minLo = Double.MAX_VALUE, maxLo = -Double.MAX_VALUE;
        for (int i = 0; i < lat.length; i++) {
            minLa = Math.min(minLa, lat[i]);
            maxLa = Math.max(maxLa, lat[i]);
            minLo = Math.min(minLo, lon[i]);
            maxLo = Math.max(maxLo, lon[i]);
        }
        int usable = MAP_SIZE - 200;
        zoom = 17;
        while (zoom > 3 && (wx(maxLo, zoom) - wx(minLo, zoom) > usable || wy(minLa, zoom) - wy(maxLa, zoom) > usable)) zoom--;
        double cx = (wx(minLo, zoom) + wx(maxLo, zoom)) / 2, cy = (wy(maxLa, zoom) + wy(minLa, zoom)) / 2;
        originX = cx - MAP_SIZE / 2.0;
        originY = cy - MAP_SIZE / 2.0;
        base = Bitmap.createBitmap(MAP_SIZE, MAP_SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(base);
        c.drawColor(Color.rgb(18, 18, 22));
        Paint tp = new Paint(Paint.FILTER_BITMAP_FLAG);
        int tx0 = (int) Math.floor(originX / TILE), tx1 = (int) Math.floor((originX + MAP_SIZE) / TILE);
        int ty0 = (int) Math.floor(originY / TILE), ty1 = (int) Math.floor((originY + MAP_SIZE) / TILE);
        File cache = new File(context.getCacheDir(), "tiles-sat");
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                Bitmap b = tile(cache, zoom, tx, ty);
                if (b != null) c.drawBitmap(b, (float) (tx * TILE - originX), (float) (ty * TILE - originY), tp);
            }
        }
        c.drawColor(0x33000000);
        Logger.line("Diário: mapa de fundo (satélite) zoom " + zoom);
    }

    /** Satélite (Esri World Imagery), cache em disco; levemente escurecido para o traçado aparecer. */
    private Bitmap tile(File cache, int z, int x, int y) {
        try {
            int max = 1 << z;
            x = ((x % max) + max) % max;
            if (y < 0 || y >= max) return null;
            File f = new File(cache, z + "/" + x + "/" + y + ".jpg");
            byte[] data;
            if (f.exists()) {
                data = java.nio.file.Files.readAllBytes(f.toPath());
            } else {
                data = net.fetch(String.format(Locale.ROOT,
                        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d", z, y, x));
                f.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    private float mx(int i) {
        return (float) (wx(lon[i], zoom) - originX);
    }

    private float my(int i) {
        return (float) (wy(lat[i], zoom) - originY) + MAP_TOP;
    }

    /** Desenha o quadro com a viagem percorrida até "t" (0..1). */
    void drawFrame(Canvas c, float t) {
        drawFrame(c, t, true);
    }

    /** full = false: só mapa + traçado (para as transições de zoom). */
    void drawFrame(Canvas c, float t, boolean full) {
        int n = lat.length;
        double target = totalM * t;
        int last = 0;
        while (last < n - 1 && dist[last + 1] <= target) last++;

        c.drawColor(Color.rgb(16, 17, 20));
        c.drawBitmap(base, 0, MAP_TOP, null);
        // degradê para o mapa se fundir com o fundo
        p.setShader(new LinearGradient(0, MAP_TOP + MAP_SIZE - 220, 0, MAP_TOP + MAP_SIZE, 0x00101114, 0xFF101114, Shader.TileMode.CLAMP));
        c.drawRect(0, MAP_TOP + MAP_SIZE - 220, W, MAP_TOP + MAP_SIZE, p);
        p.setShader(new LinearGradient(0, MAP_TOP, 0, MAP_TOP + 160, 0xFF101114, 0x00101114, Shader.TileMode.CLAMP));
        c.drawRect(0, MAP_TOP, W, MAP_TOP + 160, p);
        p.setShader(null);

        // traçado: rota inteira apagada + parte percorrida em laranja
        Path all = new Path(), done = new Path();
        for (int i = 0; i < n; i++) {
            if (i == 0) all.moveTo(mx(i), my(i)); else all.lineTo(mx(i), my(i));
        }
        for (int i = 0; i <= last; i++) {
            if (i == 0) done.moveTo(mx(i), my(i)); else done.lineTo(mx(i), my(i));
        }
        line(Color.argb(90, 255, 255, 255), 6);
        c.drawPath(all, p);
        line(Color.argb(120, 0, 0, 0), 20);
        c.drawPath(done, p);
        line(Color.rgb(255, 122, 26), 11);
        c.drawPath(done, p);
        p.setStyle(Paint.Style.FILL);
        dot(c, mx(0), my(0), Color.rgb(60, 200, 90));
        if (t >= 1f) dot(c, mx(n - 1), my(n - 1), Color.rgb(230, 40, 40));
        if (!full) return;
        drawCow(c, mx(last), my(last), screenHeading(last), 1.7f);

        // cabeçalho
        text(c, "ROLÊ DE HIMALAYAN", 60, 120, 40, Color.rgb(255, 140, 0), true);
        text(c, new SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy · HH:mm", new Locale("pt", "BR")).format(new Date(startMs)),
                60, 180, 34, Color.rgb(200, 204, 212), false);

        // números contando
        float k = Math.min(1f, t);
        int y0 = MAP_TOP + MAP_SIZE + 30;
        stat(c, 60, y0, "DISTÂNCIA", String.format(Locale.ROOT, "%.1f km", target / 1000));
        stat(c, 560, y0, "TEMPO", duration((long) (movingSec * k)));
        stat(c, 60, y0 + 150, "MÉDIA", String.format(Locale.ROOT, "%.0f km/h", avgKmh));
        stat(c, 560, y0 + 150, "SUBIDA", String.format(Locale.ROOT, "%.0f m", gain * k));

        // relevo
        float top = y0 + 370, bottom = H - 75, left = 60, right = W - 60;
        Path area = new Path();
        area.moveTo(left, bottom);
        for (int i = 0; i < n; i++) {
            float x = (float) (left + (right - left) * dist[i] / totalM);
            float y = (float) (bottom - (bottom - top) * (ele[i] - minEle) / (maxEle - minEle));
            area.lineTo(x, y);
        }
        area.lineTo(right, bottom);
        area.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(70, 255, 255, 255));
        c.drawPath(area, p);
        c.save();
        c.clipRect(left, top - 20, (float) (left + (right - left) * target / totalM), bottom);
        p.setShader(new LinearGradient(0, top, 0, bottom, Color.rgb(255, 150, 20), Color.argb(170, 255, 90, 0), Shader.TileMode.CLAMP));
        c.drawPath(area, p);
        p.setShader(null);
        c.restore();
        float cx = (float) (left + (right - left) * target / totalM);
        float cy = (float) (bottom - (bottom - top) * (ele[last] - minEle) / (maxEle - minEle));
        dot(c, cx, cy, Color.WHITE);
        text(c, String.format(Locale.ROOT, "%.0f m", ele[last]), Math.min(cx + 18, right - 120), cy - 18, 30, Color.WHITE, true);
        text(c, String.format(Locale.ROOT, "relevo · máx %.0f m · vel. máx %.0f km/h", maxEle, maxKmh), left, top - 30, 28, Color.rgb(150, 155, 165), false);
        text(c, BuildConfig.PUBLICO ? "Cow Tripper Dash" : "Tripper do Evandro", W - 300, H - 25, 24, Color.rgb(110, 114, 124), false);
    }

    private void line(int color, float width) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(width);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    private void dot(Canvas c, float x, float y, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawCircle(x, y, 17, p);
        p.setColor(color);
        c.drawCircle(x, y, 12, p);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(s, x, y, p);
    }

    private void stat(Canvas c, float x, float y, String label, String value) {
        text(c, label, x, y + 40, 30, Color.rgb(150, 155, 165), true);
        text(c, value, x, y + 112, 70, Color.WHITE, true);
    }

    private static String duration(long s) {
        return s >= 3600 ? String.format(Locale.ROOT, "%dh%02d", s / 3600, (s % 3600) / 60)
                : String.format(Locale.ROOT, "%d min", Math.max(0, s / 60));
    }

    /** Direção na tela (graus, 0 = para cima) do trecho à frente do ponto i. */
    private float screenHeading(int i) {
        int j = i;
        while (j < lat.length - 1 && dist[j] - dist[i] < 150) j++;
        int a = j == i ? Math.max(0, i - 1) : i;
        if (j == a) return 0;
        double dx = wx(lon[j], zoom) - wx(lon[a], zoom), dy = wy(lat[j], zoom) - wy(lat[a], zoom);
        return (float) Math.toDegrees(Math.atan2(dx, -dy));
    }

    private void drawCow(Canvas c, float x, float y, float headingDeg, float scale) {
        Paint w = new Paint(Paint.ANTI_ALIAS_FLAG), b = new Paint(Paint.ANTI_ALIAS_FLAG), o = new Paint(Paint.ANTI_ALIAS_FLAG);
        w.setColor(Color.rgb(250, 250, 245));
        b.setColor(Color.rgb(25, 25, 25));
        o.setColor(Color.rgb(25, 25, 25));
        o.setStyle(Paint.Style.STROKE);
        o.setStrokeWidth(3);
        c.save();
        c.translate(x, y);
        c.rotate(headingDeg);
        c.scale(scale, scale);
        c.translate(0, -5);
        RectF body = new RectF(-13, -14, 13, 24);
        c.drawRoundRect(body, 12, 13, w);
        c.drawOval(-12, -11, -2, -1, b);
        c.drawOval(3, -3, 13, 11, b);
        c.drawOval(-10, 11, 0, 19, b);
        c.drawRoundRect(body, 12, 13, o);
        c.drawOval(-7, -29, 7, -13, w);
        c.drawOval(-7, -29, 7, -13, o);
        Paint pink = new Paint(Paint.ANTI_ALIAS_FLAG);
        pink.setColor(Color.rgb(240, 160, 170));
        c.drawOval(-5.5f, -31.5f, 5.5f, -24.5f, pink);
        c.drawOval(-14, -22.5f, -6, -17.5f, b);
        c.drawOval(6, -22.5f, 14, -17.5f, b);
        c.restore();
    }

    // ---------------- voo 3D ----------------
    private int flyZoom;
    private final java.util.Map<Long, Bitmap> flyTiles = new java.util.concurrent.ConcurrentHashMap<>();
    private float smoothHeading = Float.NaN;

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /** Baixa (em paralelo) os tiles de satélite ao longo da rota no zoom do voo. */
    private void loadFlyTiles() throws Exception {
        double km = totalM / 1000;
        flyZoom = km < 8 ? 16 : km < 25 ? 15 : km < 80 ? 14 : 13;
        java.util.Set<Long> need = new java.util.HashSet<>();
        double lastD = -1e9;
        int r = 4;
        for (int i = 0; i < lat.length; i++) {
            if (dist[i] - lastD < 150 && i != lat.length - 1) continue;
            lastD = dist[i];
            int tx = (int) Math.floor(wx(lon[i], flyZoom) / TILE), ty = (int) Math.floor(wy(lat[i], flyZoom) / TILE);
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) need.add(key(tx + dx, ty + dy));
        }
        File cache = new File(context.getCacheDir(), "tiles-sat");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        for (long k : need) {
            pool.execute(() -> {
                Bitmap b = tile(cache, flyZoom, (int) (k >> 32), (int) k);
                if (b != null) flyTiles.put(k, b);
            });
        }
        pool.shutdown();
        pool.awaitTermination(3, java.util.concurrent.TimeUnit.MINUTES);
        Logger.line("Diário: voo 3D com " + flyTiles.size() + "/" + need.size() + " tiles de satélite (zoom " + flyZoom + ")");
    }

    /** Posição interpolada na distância d: {lat, lon, índice}. */
    private double[] at(double d) {
        int i = 0;
        while (i < dist.length - 2 && dist[i + 1] < d) i++;
        double seg = dist[i + 1] - dist[i];
        double t = seg <= 0 ? 0 : Math.max(0, Math.min(1, (d - dist[i]) / seg));
        return new double[]{lat[i] + (lat[i + 1] - lat[i]) * t, lon[i] + (lon[i + 1] - lon[i]) * t, i};
    }

    private float flyHeading(double d) {
        double[] a = at(d), b = at(Math.min(totalM, d + 250));
        double dx = wx(b[1], flyZoom) - wx(a[1], flyZoom), dy = wy(b[0], flyZoom) - wy(a[0], flyZoom);
        if (Math.abs(dx) + Math.abs(dy) < 1e-6) return Float.isNaN(smoothHeading) ? 0 : smoothHeading;
        return (float) Math.toDegrees(Math.atan2(dx, -dy));
    }

    private void drawFly(Canvas c, float prog) {
        double d = totalM * prog;
        double[] pos = at(d);
        float target = flyHeading(d);
        if (Float.isNaN(smoothHeading)) smoothHeading = target;
        float diff = ((target - smoothHeading + 540) % 360) - 180;
        smoothHeading += diff * 0.12f;
        double cwx = wx(pos[1], flyZoom), cwy = wy(pos[0], flyZoom);

        c.drawColor(Color.rgb(16, 17, 20));
        android.graphics.Camera cam = new android.graphics.Camera();
        cam.setLocation(0, 0, -14);
        android.graphics.Matrix m = new android.graphics.Matrix();
        cam.save();
        cam.rotateX(52);
        cam.getMatrix(m);
        cam.restore();
        m.preScale(FLY_SCALE, FLY_SCALE);
        m.preRotate(-smoothHeading);
        m.postTranslate(FOCUS_X, FOCUS_Y);
        c.save();
        c.concat(m);
        Paint tp = new Paint(Paint.FILTER_BITMAP_FLAG);
        int tx0 = (int) Math.floor(cwx / TILE), ty0 = (int) Math.floor(cwy / TILE);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                Bitmap b = flyTiles.get(key(tx0 + dx, ty0 + dy));
                if (b != null) c.drawBitmap(b, (float) ((tx0 + dx) * TILE - cwx), (float) ((ty0 + dy) * TILE - cwy), tp);
            }
        }
        Path all = new Path(), done = new Path();
        int last = (int) pos[2];
        for (int i = 0; i < lat.length; i++) {
            float x = (float) (wx(lon[i], flyZoom) - cwx), y = (float) (wy(lat[i], flyZoom) - cwy);
            if (i == 0) all.moveTo(x, y); else all.lineTo(x, y);
            if (i <= last) {
                if (i == 0) done.moveTo(x, y); else done.lineTo(x, y);
            }
        }
        done.lineTo(0, 0);
        line(Color.argb(150, 255, 255, 255), 4 / FLY_SCALE * 1.5f);
        c.drawPath(all, p);
        line(Color.argb(140, 0, 0, 0), 14 / FLY_SCALE);
        c.drawPath(done, p);
        line(Color.rgb(255, 122, 26), 8 / FLY_SCALE);
        c.drawPath(done, p);
        c.restore();

        // neblina no horizonte (esconde a falta de tiles longe)
        p.setShader(new LinearGradient(0, 0, 0, 1000, new int[]{0xFF101114, 0xFF101114, 0xCC101114, 0x00101114},
                new float[]{0f, 0.45f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawRect(0, 0, W, 1000, p);
        p.setShader(null);

        drawCow(c, FOCUS_X, FOCUS_Y, 0, 2.4f);

        // cabeçalho e painel ao vivo
        text(c, "ROLÊ DE HIMALAYAN", 60, 120, 40, Color.rgb(255, 140, 0), true);
        text(c, new SimpleDateFormat("dd/MM/yyyy · HH:mm", new Locale("pt", "BR")).format(new Date(startMs)), 60, 175, 32, Color.rgb(210, 214, 222), false);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xB3101114);
        c.drawRoundRect(30, H - 360, W - 30, H - 40, 36, 36, p);
        int i = (int) pos[2];
        stat(c, 70, H - 345, "DISTÂNCIA", String.format(Locale.ROOT, "%.1f km", d / 1000));
        stat(c, 400, H - 345, "VELOCIDADE", String.format(Locale.ROOT, "%.0f km/h", kmh[i]));
        stat(c, 760, H - 345, "ALTITUDE", String.format(Locale.ROOT, "%.0f m", ele[i]));
        drawProfile(c, 70, H - 170, W - 70, H - 70, d, false);
    }

    /** Gráfico de relevo com a parte percorrida em laranja e cursor. */
    private void drawProfile(Canvas c, float left, float top, float right, float bottom, double done, boolean label) {
        int n = lat.length;
        Path area = new Path();
        area.moveTo(left, bottom);
        for (int i = 0; i < n; i++) {
            float x = (float) (left + (right - left) * dist[i] / totalM);
            float y = (float) (bottom - (bottom - top) * (ele[i] - minEle) / (maxEle - minEle));
            area.lineTo(x, y);
        }
        area.lineTo(right, bottom);
        area.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(70, 255, 255, 255));
        c.drawPath(area, p);
        float cx = (float) (left + (right - left) * done / totalM);
        c.save();
        c.clipRect(left, top - 20, cx, bottom);
        p.setShader(new LinearGradient(0, top, 0, bottom, Color.rgb(255, 150, 20), Color.argb(170, 255, 90, 0), Shader.TileMode.CLAMP));
        c.drawPath(area, p);
        p.setShader(null);
        c.restore();
        double[] pos = at(done);
        float cy = (float) (bottom - (bottom - top) * (ele[(int) pos[2]] - minEle) / (maxEle - minEle));
        dot(c, cx, cy, Color.WHITE);
    }

    /** Quadro "frame" da animação inteira. */
    void drawTimeline(Canvas c, int frame) {
        float sec = frame / (float) FPS;
        if (sec < T_OVERVIEW) {
            drawFrame(c, 0f);
        } else if (sec < T_DIVE) {
            float u = (sec - T_OVERVIEW) / (T_DIVE - T_OVERVIEW);
            zoomOverview(c, 0, u);
            int alpha = (int) (255 * smooth(u));
            c.saveLayerAlpha(0, 0, W, H, alpha);
            drawFly(c, 0f);
            c.restore();
        } else if (sec < T_FLY_END) {
            float u = (sec - T_DIVE) / (T_FLY_END - T_DIVE);
            drawFly(c, (float) (u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2));
        } else if (sec < T_RISE) {
            float u = (sec - T_FLY_END) / (T_RISE - T_FLY_END);
            drawFly(c, 1f);
            c.saveLayerAlpha(0, 0, W, H, (int) (255 * smooth(u)));
            zoomOverview(c, lat.length - 1, 1 - u);
            c.restore();
        } else {
            drawFrame(c, 1f);
        }
    }

    private static float smooth(float u) {
        u = Math.max(0, Math.min(1, u));
        return u * u * (3 - 2 * u);
    }

    /** Visão geral ampliada (u: 0 = normal, 1 = bem perto) em volta do ponto i. */
    private void zoomOverview(Canvas c, int i, float u) {
        float k = 1 + 3.5f * smooth(u);
        c.save();
        c.scale(k, k, mx(i), my(i));
        drawFrame(c, i == 0 ? 0f : 1f, false);
        c.restore();
    }

    /** Imagem final (PNG) na galeria. */
    Uri savePng() throws Exception {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        drawFrame(new Canvas(bmp), 1f);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "role-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date(startMs)) + ".png");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tripper");
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return uri;
    }

    private static byte[] withStart(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    /** Último vídeo gerado (para conferência pelo acesso remoto). */
    static volatile File lastMp4;

    /** Vídeo H.264 1080x1920 30 fps na galeria (Movies/Tripper). */
    Uri saveMp4() throws Exception {
        File tmp = new File(context.getCacheDir(), "role.mp4");
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = codec.createInputSurface();
        codec.start();
        MediaMuxer muxer = new MediaMuxer(tmp.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1;
        boolean muxing = false;
        MediaFormat outFormat = null;
        byte[] sps = null, pps = null;
        long written = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int total = Math.round(T_TOTAL * FPS);
        try {
            for (int frame = 0; frame <= total; frame++) {
                boolean end = frame == total;
                if (!end) {
                    Canvas c = surface.lockHardwareCanvas();
                    try {
                        drawTimeline(c, frame);
                    } finally {
                        surface.unlockCanvasAndPost(c);
                    }
                } else {
                    codec.signalEndOfInputStream();
                }
                // drena o que estiver pronto
                while (true) {
                    int idx = codec.dequeueOutputBuffer(info, end ? 10_000 : 0);
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end) break;
                        continue;
                    }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFormat = codec.getOutputFormat();
                        continue;
                    }
                    if (idx < 0) continue;
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    // O encoder da MTK não põe SPS/PPS no formato (csd); pega do buffer de config ou do 1º quadro-chave.
                    if (!muxing && buf != null && info.size > 0) {
                        byte[] head = new byte[info.size];
                        buf.position(info.offset);
                        buf.get(head);
                        for (byte[] nal : RtpPacketizer.splitNals(head)) {
                            int t = nal.length > 0 ? nal[0] & 0x1F : -1;
                            if (t == 7) sps = nal;
                            if (t == 8) pps = nal;
                        }
                        if (sps != null && pps != null) {
                            MediaFormat mf = outFormat != null ? outFormat : MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
                            if (mf.getByteBuffer("csd-0") == null) {
                                mf.setByteBuffer("csd-0", ByteBuffer.wrap(withStart(sps)));
                                mf.setByteBuffer("csd-1", ByteBuffer.wrap(withStart(pps)));
                            }
                            track = muxer.addTrack(mf);
                            muxer.start();
                            muxing = true;
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxing && buf != null) {
                        // tempo fixo de 30 fps (o surface marca pelo relógio de parede)
                        info.presentationTimeUs = written++ * 1_000_000L / FPS;
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buf, info);
                    }
                    codec.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
                if (!end) Thread.sleep(1000 / FPS);
            }
        } finally {
            codec.stop();
            codec.release();
            surface.release();
            if (muxing) muxer.stop();
            muxer.release();
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, "role-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date(startMs)) + ".mp4");
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Tripper");
        Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            java.nio.file.Files.copy(tmp.toPath(), out);
        }
        File keep = new File(context.getCacheDir(), "ultimo-role.mp4");
        keep.delete();
        tmp.renameTo(keep);
        lastMp4 = keep;
        return uri;
    }
}
