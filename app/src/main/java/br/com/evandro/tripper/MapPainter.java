package br.com.evandro.tripper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mapa próprio, desenhado direto no quadro do painel (funciona com a tela do celular apagada).
 * Tiles raster escuros, cache em memória e em disco; mapa gira para a direção da moto.
 */
final class MapPainter {
    private static final int TILE = 256;
    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    static final String USER_AGENT = "NewTripperDash/" + BuildConfig.VERSION_NAME + " (Android)";

    /** Posição da moto na tela: centro na horizontal, 70% para baixo (mostra mais do caminho à frente). */
    private static final float BIKE_X = 0.5f;
    private static final float BIKE_Y = 0.70f;

    static volatile int zoomOverride = 0;
    private static final int MAX_PENDING = 24;

    private final File diskDir;
    private final LruCache<String, Bitmap> memory = new LruCache<>(96);
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private final java.util.Map<String, Long> failedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService downloader = Executors.newFixedThreadPool(3);
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** Modo noturno: inverte as cores do OSM e gira o matiz, ruas claras sobre fundo escuro. */
    private final Paint darkTile = darkPaint();

    private static Paint darkPaint() {
        android.graphics.ColorMatrix invert = new android.graphics.ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        android.graphics.ColorMatrix hue = new android.graphics.ColorMatrix(new float[]{
                -0.574f, 1.430f, 0.144f, 0, 0,
                0.426f, 0.430f, 0.144f, 0, 0,
                0.426f, 1.430f, -0.856f, 0, 0,
                0, 0, 0, 1, 0});
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix();
        dim.setScale(0.85f, 0.85f, 0.9f, 1f);
        invert.postConcat(hue);
        invert.postConcat(dim);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(invert));
        return p;
    }
    private final Paint bike = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bikeEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    private volatile Bitmap marker;
    /** Estilo do ícone sem imagem própria: "moto" ou "seta". */
    private volatile String style = "moto";
    private final android.content.SharedPreferences prefs;
    private final File markerFile;

    /** Recarrega o ícone escolhido pelo Evandro (files/icone.png); sem arquivo, volta a seta. */
    void reloadMarker() {
        style = prefs.getString("icone", "moto");
        if ("vaca".equals(style)) style = "moto";
        Bitmap b = markerFile.exists() ? BitmapFactory.decodeFile(markerFile.getPath()) : null;
        if (b != null) {
            int max = 56;
            float k = Math.min(max / (float) b.getWidth(), max / (float) b.getHeight());
            b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
        }
        marker = b;
        Logger.line(b != null ? "Mapa: ícone personalizado carregado" : "Mapa: ícone " + style);
    }

    private volatile double lat = Double.NaN;
    private volatile double lon;
    private volatile float bearing;
    private volatile float speedKmh;
    private volatile long fixAt;
    private int downloads;
    private int failures;

    private final android.net.ConnectivityManager cm;
    private volatile String lastNet = "?";

    /** Redes candidatas para baixar tiles: 4G validado primeiro, depois qualquer uma com internet, por fim a padrão. */
    private java.util.List<android.net.Network> internetNetworks() {
        java.util.List<android.net.Network> cell = new java.util.ArrayList<>();
        java.util.List<android.net.Network> other = new java.util.ArrayList<>();
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || !c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) cell.add(n);
            else other.add(n);
        }
        cell.addAll(other);
        cell.add(null);
        return cell;
    }

    /** Diagnóstico: o que cada rede responde ao DNS e ao download de um tile. */
    String networksReport() {
        StringBuilder sb = new StringBuilder();
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            sb.append(n).append(": ").append(c == null ? "?" : c.toString().replaceAll("LinkUpBandwidth.*", "")).append(" | ");
            try {
                sb.append("DNS ").append(n.getByName("tile.openstreetmap.org").getHostAddress());
            } catch (Exception e) {
                sb.append("DNS falhou ").append(e.getMessage());
            }
            try {
                HttpURLConnection con = (HttpURLConnection) n.openConnection(new URL("https://tile.openstreetmap.org/1/0/0.png"));
                con.setConnectTimeout(6000);
                sb.append(" | HTTP ").append(con.getResponseCode());
            } catch (Exception e) {
                sb.append(" | HTTP falhou ").append(e);
            }
            sb.append(SEP);
        }
        android.net.Network d = cm.getActiveNetwork();
        sb.append("padrão=").append(d).append(SEP);
        return sb.toString();
    }

    private static final String SEP = System.lineSeparator();

    /** Baixa uma URL pela primeira rede que funcionar (com o Tailscale, só a VPN deixa sair). */
    byte[] fetch(String address) throws Exception {
        Faults.network();
        URL url = new URL(address);
        Exception last = null;
        for (android.net.Network n : internetNetworks()) {
            try {
                HttpURLConnection con = (HttpURLConnection) (n != null ? n.openConnection(url) : url.openConnection());
                con.setRequestProperty("User-Agent", USER_AGENT);
                con.setConnectTimeout(8000);
                con.setReadTimeout(12000);
                try (InputStream in = con.getInputStream()) {
                    return in.readAllBytes();
                }
            } catch (Exception e) {
                last = e;
            }
        }
        // DNS do celular travado (21/11): tenta de novo com o DNS próprio
        try {
            return NetFallback.get(address);
        } catch (Exception e) {
            if (last == null) last = e;
        }
        throw last != null ? last : new java.io.IOException("sem rede");
    }

    private volatile RouteManager route;

    void setRoute(RouteManager r) {
        route = r;
    }

    double lat() {
        return lat;
    }

    double lon() {
        return lon;
    }

    MapPainter(Context context) {
        cm = context.getSystemService(android.net.ConnectivityManager.class);
        diskDir = new File(context.getCacheDir(), "tiles-osm");
        markerFile = new File(context.getFilesDir(), "icone.png");
        prefs = context.getSharedPreferences("painel", Context.MODE_PRIVATE);
        reloadMarker();
        useVector = prefs.getBoolean("vetorial", true);
        vector = new VectorMap(context, () -> {
            if (!hasFix()) return null;
            double la = Double.isNaN(lastDrawnLat) ? lat : lastDrawnLat, lo = Double.isNaN(lastDrawnLat) ? lon : lastDrawnLon;
            return new double[]{la, lo, lastDrawnBearing, (Double.isNaN(zoomShown) ? zoom() : zoomShown) - 1, speedKmh / 3.6};
        });
        if (useVector) vector.start();
        bike.setColor(Color.rgb(255, 140, 0));
        bikeEdge.setColor(Color.WHITE);
        bikeEdge.setStyle(Paint.Style.STROKE);
        bikeEdge.setStrokeWidth(3);
        text.setColor(Color.rgb(200, 200, 200));
        text.setTextSize(22);
        arrow.moveTo(0, -18);
        arrow.lineTo(12, 14);
        arrow.lineTo(0, 7);
        arrow.lineTo(-12, 14);
        arrow.close();
    }

    boolean hasFix() {
        return !Double.isNaN(lat);
    }

    /** Posição simulada (teste pelo acesso remoto): enquanto ligada, o GPS real é ignorado. */
    private volatile boolean simulated;

    void simulate(double la, double lo, float rumo, float vel) {
        simulate(la, lo, rumo, vel, 5);
    }

    void simulate(double la, double lo, float rumo, float vel, double accuracyM) {
        simulated = true;
        lat = la;
        lon = lo;
        bearing = rumo;
        speedKmh = vel;
        fixAt = System.currentTimeMillis();
        smoother.onFix(la, lo, vel / 3.6, rumo, accuracyM, fixAt);
        follower.onFix(route, la, lo, vel / 3.6, accuracyM, fixAt);
        Stats.gpsFix(fixAt);
    }

    boolean isSimulated() {
        return simulated;
    }

    void stopSimulation() {
        simulated = false;
        if (!Double.isNaN(realLat)) {
            lat = realLat;
            lon = realLon;
            bearing = (float) realBearing;
            speedKmh = 0;
            fixAt = System.currentTimeMillis();
            smoother.reset();
            smoother.onFix(lat, lon, 0, bearing, 5, fixAt);
            Logger.line("Mapa: simulação encerrada, de volta à posição real do GPS");
        }
    }

    /** Última leitura real do GPS (guardada mesmo durante simulação/teste). */
    private volatile double realLat = Double.NaN, realLon, realBearing;

    void onLocation(Location l) {
        realLat = l.getLatitude();
        realLon = l.getLongitude();
        if (l.hasBearing()) realBearing = l.getBearing();
        if (simulated || Faults.gpsBlocked) return;
        double la = l.getLatitude(), lo = l.getLongitude();
        if (Faults.gpsNoiseM > 0) {
            java.util.Random r = Faults.random;
            la += r.nextGaussian() * Faults.gpsNoiseM / 110_540.0;
            lo += r.nextGaussian() * Faults.gpsNoiseM / 102_000.0;
        }
        lat = la;
        lon = lo;
        speedKmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        if (l.hasBearing() && speedKmh > 5) bearing = l.getBearing();
        fixAt = System.currentTimeMillis();
        double acc = Math.max(l.hasAccuracy() ? l.getAccuracy() : 10, Faults.gpsNoiseM);
        // a leitura chega ao app com algum atraso: projeta a partir da hora em que foi medida (até 1,5 s)
        long ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
        long measuredAt = fixAt - Math.max(0, Math.min(1500, ageMs));
        smoother.onFix(lat, lon, speedKmh / 3.6, bearing, acc, measuredAt);
        follower.onFix(route, lat, lon, speedKmh / 3.6, acc, measuredAt);
        Stats.gpsFix(fixAt);
    }

    final MotionSmoother smoother = new MotionSmoother();
    /** Com rota: a moto anda pela linha da rota (vira a esquina junto). */
    final RouteFollower follower = new RouteFollower();
    /** Troca entre seguir a rota e o GPS livre: a diferença some em ~0,5 s em vez de pular. */
    private double blendLat, blendLon, blendBrg;
    private boolean lastOnRoute;
    private long lastFrameAt;
    private volatile double lastDrawnLat = Double.NaN, lastDrawnLon, lastDrawnBearing;
    final VectorMap vector;
    private volatile boolean useVector;

    /** Liga/desliga o mapa vetorial (desligado = mapa antigo de imagens). */
    void setVector(boolean on) {
        useVector = on;
        prefs.edit().putBoolean("vetorial", on).apply();
        if (on) vector.start();
        else vector.stop();
    }

    /** Posição suavizada mostrada no painel: {lat, lon, rumo}. */
    double[] shownPosition() {
        if (!hasFix()) return null;
        return Double.isNaN(lastDrawnLat) ? new double[]{lat, lon, bearing} : new double[]{lastDrawnLat, lastDrawnLon, lastDrawnBearing};
    }

    float speedKmh() {
        return speedKmh;
    }

    /** Teste de estresse: esvazia o cache de tiles em memória (força baixar de novo). */
    void dropMemoryTiles() {
        memory.evictAll();
    }

    int pendingTiles() {
        return pending.size();
    }

    String status() {
        return vector.status() + " " + String.format(java.util.Locale.ROOT, "gps=%s %.5f,%.5f %.0fkm/h rumo=%.0f zoom=%d tiles(baixados=%d falhas=%d mem=%d rede=%s)",
                hasFix() ? "ok" : "sem", lat, lon, speedKmh, bearing, zoom(), downloads, failures, memory.size(), lastNet);
    }

    /** Zoom pela velocidade: devagar = perto, estrada = longe. */
    private double zoomShown = Double.NaN;
    private long zoomAt;

    /** Zoom alvo pela velocidade: 17 até 25 km/h, descendo até 15 a 100 km/h (sem degraus). */
    private double zoomTarget() {
        if (zoomOverride > 0) return zoomOverride;
        // 21/11: em velocidade abria demais (z15) e as curvas sumiam; agora no máximo 1 nível (z16 a 100 km/h ou mais).
        double s = Math.max(30, Math.min(100, speedKmh));
        return 17 - (s - 30) / 70.0;
    }

    /** O zoom mostrado anda até o alvo em ~1,5 s, para não "pular" quando a velocidade muda. */
    private double smoothZoom(long nowMs) {
        double t = zoomTarget();
        if (Double.isNaN(zoomShown)) zoomShown = t;
        long dt = Math.max(0, Math.min(500, nowMs - zoomAt));
        zoomAt = nowMs;
        zoomShown += (t - zoomShown) * (1 - Math.exp(-dt / 1500.0));
        return zoomShown;
    }

    private int zoom() {
        if (zoomOverride > 0) return zoomOverride;
        if (speedKmh > 60) return 16;
        return 17;
    }

    /** Chamado pelo vídeo e pela amostra da tela do app; um de cada vez. */
    synchronized void paint(Canvas c, int w, int h) {
        long t0 = System.nanoTime();
        try {
            paintFrame(c, w, h);
        } finally {
            Stats.frame((System.nanoTime() - t0) / 1_000_000.0);
        }
    }

    private void paintFrame(Canvas c, int w, int h) {
        c.drawColor(Color.rgb(18, 18, 22));
        long t0 = System.currentTimeMillis();
        double[] free = smoother.sample(t0);
        if (!hasFix() || free == null) {
            c.drawText("Aguardando GPS…", 20, h / 2f, text);
            return;
        }
        double[] onRoute = MotionSmoother.enabled ? follower.sample(route, t0) : null;
        double[] now = onRoute != null ? onRoute : free;
        long dtFrame = lastFrameAt == 0 ? 0 : Math.max(0, t0 - lastFrameAt);
        lastFrameAt = t0;
        if ((onRoute != null) != lastOnRoute && !Double.isNaN(lastDrawnLat)) {
            blendLat = lastDrawnLat - now[0];
            blendLon = lastDrawnLon - now[1];
            blendBrg = MotionSmoother.shortest(now[2], lastDrawnBearing);
            Logger.line(onRoute != null ? "Mapa: seguindo a linha da rota" : "Mapa: fora da linha da rota, GPS livre");
        }
        lastOnRoute = onRoute != null;
        double keep = Math.exp(-dtFrame / 450.0);
        blendLat *= keep;
        blendLon *= keep;
        blendBrg *= keep;
        now = new double[]{now[0] + blendLat, now[1] + blendLon, MotionSmoother.norm(now[2] + blendBrg)};
        final double lat = now[0], lon = now[1];
        final float bearing = (float) now[2];
        if (!Double.isNaN(lastDrawnLat)) Stats.step(MotionSmoother.distanceM(lastDrawnLat, lastDrawnLon, lat, lon));
        lastDrawnLat = lat;
        lastDrawnLon = lon;
        lastDrawnBearing = bearing;
        int z = zoom();
        long nowMs = System.currentTimeMillis();
        boolean vec = useVector && vector.ready(nowMs);
        // mapa vetorial: zoom contínuo e suave pela velocidade; mapa antigo: degraus inteiros
        double zz = vec ? smoothZoom(nowMs) : z;
        double world = TILE * Math.pow(2, zz);
        double px = (lon + 180.0) / 360.0 * world;
        double sinLat = Math.sin(Math.toRadians(lat));
        double py = (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * world;

        float cx = w * BIKE_X;
        float cy = h * BIKE_Y;
        int radius = (int) Math.ceil(Math.hypot(w, h));
        int maxIndex = (1 << z) - 1;

        c.save();
        c.translate(cx, cy);
        c.rotate(-bearing);
        boolean vectorDrawn = vec && vector.draw(c, px, py, world, z);
        int tx0 = vectorDrawn ? 1 : (int) Math.floor((px - radius) / TILE);
        int tx1 = vectorDrawn ? 0 : (int) Math.floor((px + radius) / TILE);
        int ty0 = (int) Math.floor((py - radius) / TILE);
        int ty1 = (int) Math.floor((py + radius) / TILE);
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                if (ty < 0 || ty > maxIndex) continue;
                int wx = ((tx % (maxIndex + 1)) + maxIndex + 1) % (maxIndex + 1);
                Bitmap b = tile(z, wx, ty);
                if (b == null) continue;
                float left = (float) (tx * TILE - px);
                float top = (float) (ty * TILE - py);
                c.drawBitmap(b, left, top, tilePaint);
            }
        }
        RouteManager r = route;
        if (r != null) r.draw(c, z, px, py, world);
        c.restore();

        Bitmap m = marker;
        if (m != null) {
            c.drawBitmap(m, cx - m.getWidth() / 2f, cy - m.getHeight() / 2f, tilePaint);
        } else if ("moto".equals(style)) {
            drawBike(c, cx, cy);
        } else {
            c.save();
            c.translate(cx, cy);
            c.drawPath(arrow, bike);
            c.drawPath(arrow, bikeEdge);
            c.restore();
        }

        if (!simulated && System.currentTimeMillis() - fixAt > 10_000) c.drawText("sem GPS", 10, 26, text);
    }

    private static double lonToX(double lon, double world) {
        return (lon + 180.0) / 360.0 * world;
    }

    private static double latToY(double lat, double world) {
        double s = Math.sin(Math.toRadians(lat));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * world;
    }


    private static Paint bigLabel() {
        Paint p = label(Color.WHITE, 0);
        p.setTextSize(20);
        return p;
    }

    private static Paint label(int color, float halo) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(15);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        if (halo > 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(halo);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        return p;
    }

    private static final Paint bikeBody = fill(Color.rgb(255, 106, 0));
    private static final Paint bikeShine = fill(Color.argb(110, 255, 255, 255));
    private static final Paint bikeDark = fill(Color.rgb(28, 28, 32));
    private static final Paint bikeLight = fill(Color.rgb(255, 235, 140));
    private static final Paint bikeRed = fill(Color.rgb(230, 30, 30));
    private static final Paint bikeMetal = stroke(Color.rgb(150, 154, 162), 3.2f);
    private static final Paint bikeBar = stroke(Color.rgb(40, 40, 44), 3f);
    private static final Paint bikeHalo = stroke(Color.WHITE, 2f);

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    private static Paint stroke(int color, float width) {
        Paint p = fill(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    private static void oval(Canvas c, float x, float y, float rx, float ry, Paint p) {
        c.drawOval(x - rx, y - ry, x + rx, y + ry, p);
    }

    /** Moto vista de cima, frente para cima na tela. */
    private void drawBike(Canvas c, float cx, float cy) {
        drawBikeAt(c, cx, cy, 0.93f);
    }

    static void drawBikeAt(Canvas c, float cx, float cy, float scale) {
        c.save();
        c.translate(cx, cy);
        c.scale(scale, scale);
        android.graphics.RectF r = new android.graphics.RectF();
        // pneus (com contorno claro para aparecer no mapa escuro)
        r.set(-3.5f, 17, 3.5f, 37);
        c.drawRoundRect(r, 3, 3, bikeHalo);
        c.drawRoundRect(r, 3, 3, bikeDark);
        r.set(-3, -35, 3, -15);
        c.drawRoundRect(r, 3, 3, bikeHalo);
        c.drawRoundRect(r, 3, 3, bikeDark);
        // garfo e chassi
        c.drawLine(0, -15, 0, -27, bikeMetal);
        c.drawLine(0, -12, 0, 22, bikeMetal);
        // guidão, manoplas e retrovisores
        c.drawLine(-14, -17, 14, -17, bikeBar);
        oval(c, -14, -17, 2.2f, 3, bikeDark);
        oval(c, 14, -17, 2.2f, 3, bikeDark);
        c.drawLine(-9, -17, -11, -25, bikeMetal);
        c.drawLine(9, -17, 11, -25, bikeMetal);
        oval(c, -11, -26, 2.6f, 2, bikeMetal);
        oval(c, 11, -26, 2.6f, 2, bikeMetal);
        // tanque e banco
        oval(c, 0, -6, 8.5f, 12, bikeBody);
        oval(c, 0, -9, 4, 6, bikeShine);
        r.set(-5.5f, 4, 5.5f, 23);
        c.drawRoundRect(r, 5, 6, bikeDark);
        // farol e lanterna
        oval(c, 0, -19.5f, 3.4f, 2.2f, bikeLight);
        oval(c, 0, 25, 3, 1.6f, bikeRed);
        c.restore();
    }

    /**
     * Estilo noturno com ruas claras: ruas brancas viram cinza-claro, avenidas coloridas viram âmbar,
     * o resto é invertido (fundo escuro, textos brancos) mantendo o matiz.
     */
    static Bitmap nightStyle(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            if (min >= 248) {
                px[i] = 0xFFB9BCC2;
            } else if (max < 110) {
                px[i] = 0xFF0E0E10;
            } else if (r >= 225 && r >= g && g >= b - 10 && max - min >= 30) {
                px[i] = 0xFFE3A23C;
            } else {
                float ir = 255 - r, ig = 255 - g, ib = 255 - b;
                int nr = clamp(-0.574f * ir + 1.430f * ig + 0.144f * ib);
                int ng = clamp(0.426f * ir + 0.430f * ig + 0.144f * ib);
                int nb = clamp(0.426f * ir + 1.430f * ig - 0.856f * ib);
                px[i] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }

    private Bitmap tile(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        Bitmap b = memory.get(key);
        if (b != null) return b;
        Long f = failedAt.get(key);
        if (f != null && System.currentTimeMillis() - f < 15_000) return null;
        // Internet lenta: não empilhar centenas de pedidos velhos (o mapa pede de novo no próximo quadro).
        if (pending.size() >= MAX_PENDING) return null;
        if (pending.add(key)) downloader.execute(() -> load(key, z, x, y));
        return null;
    }

    private void load(String key, int z, int x, int y) {
        long t0 = System.currentTimeMillis();
        try {
            File f = new File(diskDir, key + ".png");
            Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
            if (b == null) {
                Faults.network();
                URL url = new URL(String.format(java.util.Locale.ROOT, TILE_URL, z, x, y));
                byte[] data = null;
                Exception last = null;
                for (android.net.Network n : internetNetworks()) {
                    try {
                        HttpURLConnection con = (HttpURLConnection) (n != null ? n.openConnection(url) : url.openConnection());
                        con.setRequestProperty("User-Agent", USER_AGENT);
                        con.setConnectTimeout(6000);
                        con.setReadTimeout(8000);
                        try (InputStream in = con.getInputStream()) {
                            data = in.readAllBytes();
                        }
                        String name = String.valueOf(n);
                        if (!name.equals(lastNet)) {
                            lastNet = name;
                            Logger.line("Mapa: tiles vindo pela rede " + name);
                        }
                        break;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (data == null) {
                    try {
                        data = NetFallback.get(url.toString());
                    } catch (Exception e) {
                        if (last == null) last = e;
                    }
                }
                if (data == null) throw last != null ? last : new java.io.IOException("sem rede");
                b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b == null) throw new java.io.IOException("tile inválido");
                f.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
                downloads++;
                Stats.tile(true, System.currentTimeMillis() - t0);
            }
            memory.put(key, nightStyle(b));
        } catch (Exception e) {
            failures++;
            Stats.tile(false, System.currentTimeMillis() - t0);
            failedAt.put(key, System.currentTimeMillis());
            if (failures <= 5 || failures % 50 == 0) Logger.line("Mapa: falha no tile " + key + ": " + e.getMessage());
        } finally {
            pending.remove(key);
        }
    }
}
