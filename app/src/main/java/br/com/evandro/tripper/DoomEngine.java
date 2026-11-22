package br.com.evandro.tripper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * DOOM no painel (edição "doom", só para vídeo). O motor nativo (doomgeneric, GPL) só existe
 * nessa edição; nas outras esta classe nunca é carregada.
 */
final class DoomEngine {
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, FIRE = 4, USE = 5, ENTER = 6, ESC = 7;

    private static volatile boolean started;
    private static Bitmap bitmap;
    private static ByteBuffer pixels;
    private static int lastSeq = -1;
    private static final Paint PIXELATED = new Paint();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable[] releases = new Runnable[8];

    private DoomEngine() {}

    static boolean running() {
        return started;
    }

    static synchronized boolean start(Context context) {
        if (started) return true;
        File wad = pickWad(context);
        if (wad == null) {
            Logger.line("DOOM: nenhum WAD encontrado");
            return false;
        }
        System.loadLibrary("cowdoom");
        int w = nativeWidth(), h = nativeHeight();
        pixels = ByteBuffer.allocateDirect(w * h * 4);
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        String dir = context.getFilesDir().getAbsolutePath();
        Thread t = new Thread(null, () -> nativeRun(dir, wad.getAbsolutePath()), "doom", 16L << 20);
        t.setDaemon(true);
        t.start();
        started = true;
        Logger.line("DOOM: rodando " + wad.getName() + " (" + w + "x" + h + ")");
        return true;
    }

    /** DOOM.WAD comprado (pasta do app no armazenamento) tem prioridade; senão o shareware embutido. */
    private static File pickWad(Context context) {
        File ext = context.getExternalFilesDir(null);
        if (ext != null) {
            File[] fs = ext.listFiles();
            if (fs != null) for (String want : new String[]{"doom.wad", "doom2.wad", "doomu.wad"})
                for (File f : fs) if (f.getName().equalsIgnoreCase(want)) return f;
        }
        File shareware = new File(context.getFilesDir(), "doom1.wad");
        if (shareware.length() > 0) return shareware;
        try (InputStream in = context.getAssets().open("doom1.wad"); OutputStream out = new FileOutputStream(shareware)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return shareware;
        } catch (Exception e) {
            Logger.line("DOOM: sem doom1.wad nos assets: " + e);
            return null;
        }
    }

    /** Último quadro do jogo (o mesmo Bitmap é reaproveitado). */
    static synchronized Bitmap frame() {
        if (!started) return null;
        int seq = nativeFrame(pixels);
        if (seq >= 0 && seq != lastSeq) {
            pixels.rewind();
            bitmap.copyPixelsFromBuffer(pixels);
            pixels.rewind();
            lastSeq = seq;
        }
        return bitmap;
    }

    /** Desenha o jogo no quadro do painel, mantendo a proporção e os pixels "duros". */
    static void paint(Canvas c, int w, int h) {
        c.drawColor(Color.BLACK);
        Bitmap b = frame();
        if (b == null) return;
        float s = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        float x = (w - dw) / 2f, y = (h - dh) / 2f;
        c.drawBitmap(b, null, new RectF(x, y, x + dw, y + dh), PIXELATED);
    }

    static void key(int which, boolean down) {
        if (!started) return;
        nativeKey(nativeKeyCode(which), down);
    }

    /** O painel só manda "apertou" (sem soltar): segura a tecla por um tempo e solta sozinho. */
    static void pulse(int which, long ms) {
        if (!started) return;
        Runnable old = releases[which];
        if (old != null) main.removeCallbacks(old);
        else key(which, true);
        Runnable up = () -> {
            releases[which] = null;
            key(which, false);
        };
        releases[which] = up;
        main.postDelayed(up, ms);
    }

    /** Botões do guidão viram controle. Mapeamento provisório: o log mostra o código de cada botão. */
    static boolean dashButton(int code) {
        if (!started) return false;
        int which;
        long ms;
        switch (code) {
            case 19: which = UP; ms = 450; break;      // zoom +
            case 20: which = DOWN; ms = 450; break;    // zoom -
            case 9: case 6: which = RIGHT; ms = 220; break;  // próxima faixa / volume +
            case 10: case 7: which = LEFT; ms = 220; break;  // faixa anterior / volume -
            case 21: which = FIRE; ms = 160; break;    // recentralizar
            case 5: case 8: which = USE; ms = 160; break;    // play/pausa / mudo
            case 25: which = ENTER; ms = 160; break;   // ir ao POI
            default: return false;
        }
        Logger.line("DOOM: botão " + code + " → " + new String[]{"frente", "trás", "esquerda", "direita", "tiro", "usar", "enter", "esc"}[which]);
        pulse(which, ms);
        return true;
    }

    private static native int nativeWidth();
    private static native int nativeHeight();
    private static native int nativeKeyCode(int which);
    private static native void nativeKey(int key, boolean down);
    private static native int nativeFrame(ByteBuffer dst);
    private static native void nativeRun(String dir, String wad);
}
