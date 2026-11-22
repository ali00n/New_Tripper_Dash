package br.com.evandro.tripper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.util.Map;

/**
 * Impressão digital da seta do Google Maps (16x16, 1 bit por pixel).
 * Guarda a seta em PNG para a gente rotular, e lembra seta -> código que o texto já revelou.
 */
final class IconHash {
    private static final int SIZE = 16;
    private static final int MAX_DISTANCE = 20;
    private final SharedPreferences learned;
    private final File iconDir;

    IconHash(Context context) {
        learned = context.getSharedPreferences("setas_aprendidas", Context.MODE_PRIVATE);
        iconDir = new File(context.getExternalFilesDir(null), "setas");
        iconDir.mkdirs();
    }

    static Bitmap toBitmap(Context context, Icon icon) {
        if (icon == null) return null;
        try {
            Drawable d = icon.loadDrawable(context);
            if (d == null) return null;
            Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 64, 64);
            d.draw(c);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    static String hash(Bitmap source) {
        Bitmap small = Bitmap.createScaledBitmap(source, SIZE, SIZE, true);
        BigInteger bits = BigInteger.ZERO;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int p = small.getPixel(x, y);
                int lum = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3;
                boolean on = Color.alpha(p) > 100 && lum > 60;
                if (on) bits = bits.setBit(y * SIZE + x);
            }
        }
        return bits.toString(16);
    }

    void saveSample(String hash, Bitmap bitmap) {
        File f = new File(iconDir, hash + ".png");
        if (f.exists()) return;
        try (FileOutputStream out = new FileOutputStream(f)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
        }
    }

    void learn(String hash, int maneuver) {
        if (learned.getInt(hash, -999) != maneuver) {
            learned.edit().putInt(hash, maneuver).apply();
            Logger.line("   aprendi a seta " + hash.substring(0, Math.min(8, hash.length())) + " = manobra " + maneuver);
        }
    }

    /** Código conhecido para essa seta (aceita setas parecidas), ou NavParser.UNKNOWN. */
    int lookup(String hash) {
        BigInteger target = new BigInteger(hash, 16);
        int best = NavParser.UNKNOWN;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, ?> e : learned.getAll().entrySet()) {
            int distance = target.xor(new BigInteger(e.getKey(), 16)).bitCount();
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (Integer) e.getValue();
            }
        }
        return bestDistance <= MAX_DISTANCE ? best : NavParser.UNKNOWN;
    }
}
