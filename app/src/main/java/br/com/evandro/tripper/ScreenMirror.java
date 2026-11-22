package br.com.evandro.tripper;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Espelha a tela do celular (o Google Maps) em baixa resolução e entrega
 * a faixa do meio, no formato do painel (526x300), para o projetor de vídeo.
 */
final class ScreenMirror {
    private static final int CAPTURE_WIDTH = 540;
    private static final long MIN_FRAME_MS = 90;

    /** Centro vertical do recorte (0 = topo, 1 = base). Ajustável pelo acesso remoto. */
    static volatile float centerY = 0.55f;

    /** Quanto "afastar": 1 = faixa exata; 1.4 = pega 40% a mais de altura (com bordas pretas nas laterais). */
    static volatile float zoomOut = 1.4f;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Object lock = new Object();
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Bitmap work;
    private Bitmap latest;
    private int contentWidth;
    private int captureHeight;
    private long lastFrameAt;
    private volatile boolean running;

    boolean isRunning() {
        return running;
    }

    boolean start(Context context, int resultCode, Intent data, Runnable onStopped) {
        stop();
        try {
            MediaProjectionManager mpm = context.getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) {
                Logger.line("Espelho: o Android não liberou a captura");
                return false;
            }
            thread = new HandlerThread("espelho");
            thread.start();
            Handler h = new Handler(thread.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Logger.line("Espelho: captura encerrada pelo Android");
                    release();
                    if (onStopped != null) onStopped.run();
                }
            }, h);

            DisplayMetrics real = new DisplayMetrics();
            context.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(real);
            captureHeight = Math.round(CAPTURE_WIDTH * (float) real.heightPixels / real.widthPixels);
            reader = ImageReader.newInstance(CAPTURE_WIDTH, captureHeight, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onImage, h);
            display = projection.createVirtualDisplay("tripper-espelho", CAPTURE_WIDTH, captureHeight,
                    real.densityDpi * CAPTURE_WIDTH / real.widthPixels,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
            running = true;
            Logger.line("Espelho ligado: captura " + CAPTURE_WIDTH + "x" + captureHeight
                    + " (tela " + real.widthPixels + "x" + real.heightPixels + ")");
            return true;
        } catch (Exception e) {
            Logger.line("Espelho: ERRO ao ligar " + e);
            release();
            return false;
        }
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < MIN_FRAME_MS) return;
            lastFrameAt = now;
            Image.Plane plane = img.getPlanes()[0];
            int rowPixels = plane.getRowStride() / plane.getPixelStride();
            if (work == null || work.getWidth() != rowPixels || work.getHeight() != img.getHeight()) {
                work = Bitmap.createBitmap(rowPixels, img.getHeight(), Bitmap.Config.ARGB_8888);
            }
            ByteBuffer buf = plane.getBuffer();
            buf.rewind();
            work.copyPixelsFromBuffer(buf);
            synchronized (lock) {
                Bitmap t = latest;
                latest = work;
                work = t;
                contentWidth = img.getWidth();
            }
        } catch (Exception e) {
            Logger.line("Espelho: erro no quadro " + e);
        } finally {
            if (img != null) img.close();
        }
    }

    /** Desenha o recorte no quadro do painel. Retorna false se ainda não há imagem. */
    boolean paint(Canvas canvas, int width, int height) {
        synchronized (lock) {
            if (!running || latest == null) return false;
            int srcW = contentWidth;
            float z = Math.max(1f, zoomOut);
            int srcH = Math.min(latest.getHeight(), Math.round(srcW * (float) height / width * z));
            int center = Math.round(latest.getHeight() * centerY);
            int top = Math.max(0, Math.min(latest.getHeight() - srcH, center - srcH / 2));
            int dstW = Math.min(width, Math.round(height * (float) srcW / srcH));
            int left = (width - dstW) / 2;
            canvas.drawColor(android.graphics.Color.BLACK);
            canvas.drawBitmap(latest, new Rect(0, top, srcW, top + srcH), new Rect(left, 0, left + dstW, height), paint);
            return true;
        }
    }

    void stop() {
        MediaProjection p = projection;
        release();
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) { }
        }
    }

    private void release() {
        running = false;
        try { if (display != null) display.release(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        if (thread != null) thread.quitSafely();
        display = null;
        reader = null;
        thread = null;
        projection = null;
        synchronized (lock) {
            latest = null;
            work = null;
        }
    }
}
