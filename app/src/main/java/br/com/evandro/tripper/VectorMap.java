package br.com.evandro.tripper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.Style;
import org.maplibre.android.snapshotter.MapSnapshotter;

/**
 * Mapa vetorial (MapLibre) para o painel, desenhado fora da tela: tira "fotos" do mapa em sequência,
 * já na posição prevista da moto, e a cada quadro do vídeo cola a última foto deslocada até a posição suavizada.
 * Assim o vídeo anda a 12 q/s mesmo que cada foto demore mais que um quadro.
 */
final class VectorMap {
    /** Foto maior que a tela, para sobrar borda ao deslocar e girar entre uma foto e outra. */
    static final int SNAP_W = 760, SNAP_H = 560;
    /** 1.0 (antes 1.5): o painel tem 526x300, mais pixels só esquentavam o celular (2,25x menos trabalho). */
    static final float PIXEL_RATIO = 1.0f;
    /** 4 fotos/s no máximo (antes 10): o vídeo continua a 12 q/s deslocando a última foto. */
    private static final long MIN_GAP_MS = 250;
    /** Sem internet: usa só o que já está guardado (cache + mapa baixado) por este tempo e tenta a rede de novo. */
    private static final long OFFLINE_HOLD_MS = 60_000;
    private volatile boolean offlineFallback;
    /** Foto que não volta nesse tempo é cancelada. */
    private static final long STUCK_MS = 5000;
    private volatile int shotToken;
    /** Intervalo extra entre fotos quando o celular está quente (ThermalGuard). */
    static volatile long extraGapMs = 0;
    /** Moto parada e câmera igual: não tira foto nova (economiza bateria e esquenta menos). */
    private static final long IDLE_REFRESH_MS = 3000;
    private static final long FAIL_RETRY_MS = 2000;

    /** Câmera pedida: centro da foto = posição da moto. */
    static final class Shot {
        final Bitmap bitmap;
        final double lat, lon, bearing, zoom;
        final long at;

        Shot(Bitmap bitmap, double lat, double lon, double bearing, double zoom, long at) {
            this.bitmap = bitmap;
            this.lat = lat;
            this.lon = lon;
            this.bearing = bearing;
            this.zoom = zoom;
            this.at = at;
        }
    }

    interface Camera {
        /** {lat, lon, rumo, zoom, velocidade m/s} da moto agora, ou null sem GPS. */
        double[] now();
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Camera camera;
    private MapSnapshotter snapshotter;
    private volatile Shot last;
    private volatile boolean busy;
    private volatile boolean running;
    private volatile long lastLatencyMs = 300;
    private volatile int shots, errors;
    private volatile String lastError = "";
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    VectorMap(Context context, Camera camera) {
        this.context = context.getApplicationContext();
        this.camera = camera;
    }

    /** Liga o laço de fotos (tudo do MapLibre roda na thread principal). */
    void start() {
        main.post(() -> {
            if (running) return;
            try {
                MapLibre.getInstance(context);
                // Mesmo cliente de internet do app: com DNS de reserva se o do celular travar.
                org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(NetFallback.client);
                Style.Builder style = new Style.Builder().fromJson(MapStyle.json());
                // Ícones entram DEPOIS de montar: com estilo em JSON o MapLibre 13 tenta pôr as imagens
                // antes de ter o ponteiro nativo ("invalid native peer").
                MapSnapshotter.Options o = new MapSnapshotter.Options(SNAP_W, SNAP_H)
                        .withStyleBuilder(style)
                        .withPixelRatio(PIXEL_RATIO)
                        .withLogo(false);
                double[] c = camera.now();
                if (c != null) o.withCameraPosition(position(c));
                snapshotter = new MapSnapshotter(context, o);
                for (String k : MapStyle.ICONS) snapshotter.addImage(k, MapStyle.icon(k, PIXEL_RATIO), false);
                running = true;
                Logger.line("Mapa vetorial: ligado (" + SNAP_W + "x" + SNAP_H + " @" + PIXEL_RATIO + ")");
                next();
            } catch (Throwable t) {
                lastError = String.valueOf(t);
                Logger.line("Mapa vetorial: não ligou, fica o mapa antigo: " + t);
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(8, st.length); i++) Logger.line("   em " + st[i]);
                if (t.getCause() != null) Logger.line("   causa: " + t.getCause());
                running = false;
            }
        });
    }

    void stop() {
        main.post(() -> {
            running = false;
            if (snapshotter != null) snapshotter.cancel();
        });
    }

    /** Tem foto recente o bastante para usar (senão o pintor usa o mapa antigo). */
    boolean ready(long nowMs) {
        Shot s = last;
        return running && s != null && nowMs - s.at < 8000;
    }

    String status() {
        Shot s = last;
        return "vetorial=" + (running ? "ligado" : "desligado") + " fotos=" + shots + " erros=" + errors
                + " latência=" + lastLatencyMs + "ms" + (s != null ? " última há " + (System.currentTimeMillis() - s.at) + "ms" : "")
                + (lastError.isEmpty() ? "" : " erro=" + lastError);
    }

    private static CameraPosition position(double[] c) {
        return new CameraPosition.Builder().target(new LatLng(c[0], c[1])).zoom(c[3]).bearing(c[2]).tilt(0).build();
    }

    private void next() {
        if (!running || busy) return;
        double[] c = camera.now();
        if (c == null) {
            main.postDelayed(this::next, 500);
            return;
        }
        Shot prev = last;
        if (prev != null && System.currentTimeMillis() - prev.at < IDLE_REFRESH_MS
                && MotionSmoother.distanceM(prev.lat, prev.lon, c[0], c[1]) < 2
                && Math.abs(MotionSmoother.shortest(prev.bearing, c[2])) < 2 && Math.abs(prev.zoom - c[3]) < 0.03) {
            main.postDelayed(this::next, 200);
            return;
        }
        // pede a foto onde a moto vai estar quando ela ficar pronta
        // sem "rota presumida" (teste remoto), a foto sai exatamente onde a moto está
        double ahead = MotionSmoother.enabled ? c[4] * lastLatencyMs / 1000.0 : 0;
        double rad = Math.toRadians(c[2]);
        double la = c[0] + ahead * Math.cos(rad) / 110_540.0;
        double lo = c[1] + ahead * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(c[0])));
        double[] cam = {la, lo, c[2], c[3]};
        busy = true;
        long t0 = System.currentTimeMillis();
        final int token = ++shotToken;
        // 22/11: uma foto presa esperando a internet congelava o mapa para sempre (painel preto a 180 km/h).
        main.postDelayed(() -> {
            if (!busy || token != shotToken) return;
            errors++;
            lastError = "foto presa por " + STUCK_MS / 1000 + " s";
            Logger.line("Mapa vetorial: foto presa há " + STUCK_MS / 1000 + " s, cancelando e usando o mapa guardado");
            try { snapshotter.cancel(); } catch (Throwable ignored) { }
            shotToken++;
            busy = false;
            if (!offlineFallback) goOffline();
            main.postDelayed(this::next, MIN_GAP_MS);
        }, STUCK_MS);
        try {
            snapshotter.setCameraPosition(position(cam));
            snapshotter.start(snap -> {
                if (token != shotToken) return;
                long dt = System.currentTimeMillis() - t0;
                lastLatencyMs = (lastLatencyMs * 3 + dt) / 4;
                last = new Shot(snap.getBitmap(), cam[0], cam[1], cam[2], cam[3], System.currentTimeMillis());
                shots++;
                if (shots == 1) Logger.line("Mapa vetorial: 1ª foto pronta em " + dt + " ms");
                busy = false;
                main.postDelayed(this::next, Math.max(MIN_GAP_MS, extraGapMs));
            }, err -> {
                if (token != shotToken) return;
                errors++;
                lastError = String.valueOf(err);
                if (errors <= 5 || errors % 50 == 0) Logger.line("Mapa vetorial: erro na foto " + err);
                busy = false;
                if (!offlineFallback && isNetworkError(String.valueOf(err))) {
                    goOffline();
                    main.postDelayed(this::next, MIN_GAP_MS);
                    return;
                }
                main.postDelayed(this::next, FAIL_RETRY_MS);
            });
        } catch (Throwable t) {
            busy = false;
            errors++;
            lastError = String.valueOf(t);
            main.postDelayed(this::next, FAIL_RETRY_MS);
        }
    }

    private static boolean isNetworkError(String e) {
        String s = e.toLowerCase(java.util.Locale.ROOT);
        return s.contains("resolve host") || s.contains("failed to connect") || s.contains("timeout")
                || s.contains("timed out") || s.contains("network") || s.contains("connection");
    }

    /** Internet caiu (ou DNS falhou): MapLibre passa a desenhar só do cache/mapa baixado, sem travar esperando a rede. */
    private void goOffline() {
        offlineFallback = true;
        Logger.line("Mapa vetorial: sem internet, usando o mapa guardado no celular por " + OFFLINE_HOLD_MS / 1000 + " s");
        OfflineMaps.forceOffline(true);
        main.postDelayed(() -> {
            offlineFallback = false;
            OfflineMaps.forceOffline(null);
        }, OFFLINE_HOLD_MS);
    }

    /**
     * Desenha a última foto com o centro (posição da foto) levado até onde a moto está agora.
     * O canvas já vem transladado para a moto e girado pelo rumo atual (mesmo sistema dos tiles antigos).
     */
    boolean draw(Canvas c, double px, double py, double world, int zoom) {
        Shot s = last;
        if (s == null) return false;
        double sWorld = 512 * Math.pow(2, s.zoom); // zoom do MapLibre conta em blocos de 512 px
        double k = world / sWorld; // diferença de zoom entre a foto e o quadro
        double sx = (s.lon + 180.0) / 360.0 * world - px;
        double sin = Math.sin(Math.toRadians(s.lat));
        double sy = (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * world - py;
        c.save();
        c.translate((float) sx, (float) sy);
        c.rotate((float) s.bearing);
        float scale = (float) (k / PIXEL_RATIO);
        c.scale(scale, scale);
        c.drawBitmap(s.bitmap, -s.bitmap.getWidth() / 2f, -s.bitmap.getHeight() / 2f, blit);
        c.restore();
        return true;
    }
}
