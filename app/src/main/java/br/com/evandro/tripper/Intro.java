package br.com.evandro.tripper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Abertura ao conectar no painel: moto + barra de carregando rápida (1,5 s),
 * Depois disso, o mapa.
 */
final class Intro {
    /** O painel leva ~1 a 2 s para começar a mostrar o vídeo: a moto fica parada esse tempo antes de aparecer. */
    static final long HOLD_MS = 1200;
    static final long COW_MS = 2000;
    static final String APP_NAME = "NEW TRIPPER DASH";
    static final long TOTAL_MS = HOLD_MS + COW_MS;

    private volatile long startedAt;
    private final Paint bg = new Paint(), bar = new Paint(Paint.ANTI_ALIAS_FLAG), barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG), bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleText = new Paint(Paint.ANTI_ALIAS_FLAG);

    Intro() {
        bg.setColor(Color.rgb(16, 19, 24));
        bar.setColor(Color.rgb(66, 146, 255));
        barBg.setColor(Color.rgb(40, 46, 56));
        title.setColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        small.setColor(Color.rgb(170, 178, 190));
        small.setTextSize(15);
        bubble.setColor(Color.WHITE);
        bubbleText.setColor(Color.rgb(20, 20, 20));
        bubbleText.setTextSize(22);
        bubbleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bubbleText.setTextAlign(Paint.Align.CENTER);
    }

    void start() {
        startedAt = System.currentTimeMillis();
    }

    boolean active() {
        long t = System.currentTimeMillis() - startedAt;
        return startedAt > 0 && t >= 0 && t < TOTAL_MS;
    }

    /** Desenha o quadro da abertura no celular (tela retangular). */
    void paint(Canvas c, int w, int h) {
        paint(c, w, h, false);
    }

    /**
     * panel=true: painel da moto é redondo, o vídeo aparece dentro de um círculo e perde laterais e o arco de cima.
     * Tudo fica concentrado no miolo (x ~95 a 430, y ~70 a 280).
     */
    void paint(Canvas c, int w, int h, boolean panel) {
        long t = System.currentTimeMillis() - startedAt;
        c.drawRect(0, 0, w, h, bg);
        if (panel) {
            if (t < HOLD_MS) paintPanelBike(c, w, h, 0);
            else paintPanelBike(c, w, h, Math.min(t - HOLD_MS, COW_MS));
            return;
        }
        if (t < HOLD_MS) paintBike(c, w, h, 0);
        else paintBike(c, w, h, Math.min(t - HOLD_MS, COW_MS));
    }

    private void paintBike(Canvas c, int w, int h, long t) {
        float k = t / (float) COW_MS;
        // moto grande
        float bob = (float) Math.sin(t / 70.0) * 3;
        c.save();
        c.translate(w / 2f - 40, h / 2f - 18 + bob);
        c.rotate(-90);
        MapPainter.drawBikeAt(c, 0, 0, 2.6f);
        c.restore();
        // nome do app
        Paint name = new Paint(title);
        name.setTextSize(24);
        name.setColor(Color.rgb(242, 227, 160));
        name.setTextAlign(Paint.Align.CENTER);
        name.setLetterSpacing(0.12f);
        c.drawText(APP_NAME, w / 2f, 40, name);
        // barra de carregando, rápida
        float p = Math.min(1f, k / 0.9f);
        float x0 = 113, x1 = w - 113, y = h - 52;
        c.drawRoundRect(x0, y, x1, y + 10, 5, 5, barBg);
        c.drawRoundRect(x0, y, x0 + (x1 - x0) * p, y + 10, 5, 5, bar);
        small.setTextAlign(Paint.Align.CENTER);
        c.drawText("carregando " + Math.round(p * 100) + "%", w / 2f, y + 32, small);
    }

    private void paintPanelBike(Canvas c, int w, int h, long t) {
        float k = t / (float) COW_MS;
        Paint nm = new Paint(title);
        nm.setTextSize(19);
        nm.setColor(Color.rgb(242, 227, 160));
        nm.setTextAlign(Paint.Align.CENTER);
        nm.setLetterSpacing(0.1f);
        c.drawText(APP_NAME, w / 2f, 92, nm);
        float bob = (float) Math.sin(t / 70.0) * 2;
        c.save();
        c.translate(w / 2f - 30, 165 + bob);
        c.rotate(-90);
        MapPainter.drawBikeAt(c, 0, 0, 1.9f);
        c.restore();
        float p = Math.min(1f, k / 0.9f);
        float x0 = 165, x1 = w - 165, y = 228;
        c.drawRoundRect(x0, y, x1, y + 8, 4, 4, barBg);
        c.drawRoundRect(x0, y, x0 + (x1 - x0) * p, y + 8, 4, 4, bar);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(13);
        c.drawText("carregando " + Math.round(p * 100) + "%", w / 2f, y + 26, small);
        small.setTextSize(15);
    }
}
